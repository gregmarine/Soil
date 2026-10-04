package com.symmetricalpalmtree.soil.restore

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

/**
 * The staging half of L1 (D2): a directory beside the live library that is wiped at the top of
 * every attempt, and a write path where a dropped read can never leave a truncated file under a
 * name the commit would install. The short-write case is the one that matters most — a silently
 * short staging set would be committed as the entire library.
 */
class RestoreStagingTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("restore-staging-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun item(name: String, kind: ItemKind, relativePath: String, size: Long = 4L) =
        Item(name, size, kind, relativePath)

    private fun writer(bytes: ByteArray, report: Long = bytes.size.toLong()): (OutputStream) -> Long =
        { out -> out.write(bytes); report }

    // ── reset / discard ──────────────────────────────────────────────────────

    @Test
    fun `reset wipes leftovers and creates Garden`() {
        val dir = RestoreStaging.dir(root)
        File(dir, "garden").mkdirs()
        val leftover = File(dir, "garden/old.soil").apply { writeText("stale") }
        assertTrue(leftover.exists())

        val fresh = RestoreStaging.reset(root)

        assertEquals(dir.path, fresh.path)
        assertTrue(fresh.isDirectory)
        assertTrue(File(fresh, "garden").isDirectory)
        assertFalse(leftover.exists())
    }

    @Test
    fun `discard removes the whole directory`() {
        RestoreStaging.reset(root)
        File(RestoreStaging.dir(root), "soil.db").writeText("x")
        RestoreStaging.discard(root)
        assertFalse(RestoreStaging.dir(root).exists())
    }

    // ── targetFor ────────────────────────────────────────────────────────────

    @Test
    fun `targetFor maps each kind to the live layout`() {
        val dir = RestoreStaging.reset(root)
        assertEquals(
            File(dir, "soil.db").path,
            RestoreStaging.targetFor(dir, item("soil.db", ItemKind.INDEX, "soil.db")).path,
        )
        assertEquals(
            File(dir, "soil.db-wal").path,
            RestoreStaging.targetFor(dir, item("soil.db-wal", ItemKind.INDEX_WAL, "soil.db-wal")).path,
        )
        assertEquals(
            File(dir, "garden/a.soil").path,
            RestoreStaging.targetFor(dir, item("a.soil", ItemKind.SOIL, "garden/a.soil")).path,
        )
        assertEquals(
            File(dir, "garden/a.soil-wal").path,
            RestoreStaging.targetFor(dir, item("a.soil-wal", ItemKind.SOIL_WAL, "garden/a.soil-wal")).path,
        )
        assertEquals(
            File(dir, "garden/pkg.db").path,
            RestoreStaging.targetFor(dir, item("pkg.db", ItemKind.STORE, "garden/pkg.db")).path,
        )
        assertEquals(
            File(dir, "garden/pkg.db-wal").path,
            RestoreStaging.targetFor(dir, item("pkg.db-wal", ItemKind.STORE_WAL, "garden/pkg.db-wal")).path,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `targetFor refuses a path that escapes the staging directory`() {
        val dir = RestoreStaging.reset(root)
        RestoreStaging.targetFor(dir, item("soil.db", ItemKind.INDEX, "../soil.db"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `targetFor refuses an escape through Garden`() {
        val dir = RestoreStaging.reset(root)
        RestoreStaging.targetFor(dir, item("a.soil", ItemKind.SOIL, "garden/../../a.soil"))
    }

    // ── writeStaged ──────────────────────────────────────────────────────────

    @Test
    fun `a complete write lands under the real name and leaves no part`() {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "soil.db")
        val bytes = "hello".toByteArray()

        assertTrue(RestoreStaging.writeStaged(target, bytes.size.toLong(), writer(bytes)))

        assertEquals("hello", target.readText())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a short write leaves neither the target nor the part`() {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "soil.db")

        assertFalse(RestoreStaging.writeStaged(target, 99L, writer("short".toByteArray())))

        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a lying byte count is caught even when the file is the expected length`() {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "soil.db")
        val bytes = "hello".toByteArray()

        // The stream wrote the whole file but reported fewer bytes: still a failure.
        assertFalse(RestoreStaging.writeStaged(target, bytes.size.toLong(), writer(bytes, report = 2L)))
        assertFalse(target.exists())
    }

    @Test
    fun `an exception in the writer leaves nothing behind and never throws`() {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "soil.db")

        val ok = RestoreStaging.writeStaged(target, 5L) { throw IOException("dropped read") }

        assertFalse(ok)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `an unknown expected size accepts any length`() {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")
        val bytes = "whatever".toByteArray()

        assertTrue(RestoreStaging.writeStaged(target, -1L, writer(bytes, report = 0L)))
        assertEquals("whatever", target.readText())
    }

    @Test
    fun `a second write replaces an existing target`() {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "soil.db")
        RestoreStaging.writeStaged(target, 3L, writer("old".toByteArray()))

        assertTrue(RestoreStaging.writeStaged(target, 3L, writer("new".toByteArray())))
        assertEquals("new", target.readText())
    }

    // ── writeStagedVia (arc 27 / L4) ─────────────────────────────────────────

    @Test
    fun `a filled part lands under the real name and leaves no part`() = runBlocking {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")
        var handed: File? = null

        val ok = RestoreStaging.writeStagedVia(target, 5L) { part ->
            handed = part
            part.writeText("hello")
            5L
        }

        assertTrue(ok)
        assertEquals("hello", target.readText())
        assertFalse(File(target.path + ".part").exists())
        // The caller was handed the `.part` sibling, never the real name — a dropped fill can
        // never leave a truncated file under a name the commit would install.
        assertEquals(File(target.path + ".part").path, handed?.path)
    }

    @Test
    fun `a short fill leaves neither the target nor the part`() = runBlocking {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")

        val ok = RestoreStaging.writeStagedVia(target, 99L) { part ->
            part.writeText("short")
            5L
        }

        assertFalse(ok)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a lying byte count is caught even when the part is the expected length`() = runBlocking {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")

        // The provider said it wrote 2 bytes; 5 landed. Three accounts must agree.
        val ok = RestoreStaging.writeStagedVia(target, 5L) { part ->
            part.writeText("hello")
            2L
        }

        assertFalse(ok)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a fill that reports a failure leaves nothing behind`() = runBlocking {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")

        val ok = RestoreStaging.writeStagedVia(target, 5L) { part ->
            part.writeText("hello")
            -1L
        }

        assertFalse(ok)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a fill that throws is a false, never a throw of its own`() = runBlocking {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")

        val ok = RestoreStaging.writeStagedVia(target, 5L) { throw IOException("the link died") }

        assertFalse(ok)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `a stale part from a killed attempt is replaced, not appended to`() = runBlocking {
        val dir = RestoreStaging.reset(root)
        val target = File(dir, "garden/a.soil")
        File(dir, "garden").mkdirs()
        File(target.path + ".part").writeText("leftover from a killed run")

        val ok = RestoreStaging.writeStagedVia(target, 5L) { part ->
            assertEquals(0L, part.length())
            part.writeText("hello")
            5L
        }

        assertTrue(ok)
        assertEquals("hello", target.readText())
    }

    @Test
    fun `writeStagedVia creates the parent directory it needs`() = runBlocking {
        val dir = RestoreStaging.dir(root)
        val target = File(dir, "garden/a.soil")
        assertFalse(dir.exists())

        val ok = RestoreStaging.writeStagedVia(target, 1L) { part -> part.writeText("x"); 1L }

        assertTrue(ok)
        assertEquals("x", target.readText())
    }

    // ── fits ─────────────────────────────────────────────────────────────────

    @Test
    fun `fits leaves the headroom behind`() {
        assertTrue(RestoreStaging.fits(100L, 100L + RestoreStaging.HEADROOM_BYTES))
        assertFalse(RestoreStaging.fits(100L, 99L + RestoreStaging.HEADROOM_BYTES))
        assertTrue(RestoreStaging.fits(100L, 1_000L, headroom = 900L))
        assertFalse(RestoreStaging.fits(100L, 1_000L, headroom = 901L))
    }

    @Test
    fun `unknown usable space never fits`() {
        assertFalse(RestoreStaging.fits(0L, -1L))
        assertFalse(RestoreStaging.fits(0L, -1L, headroom = 0L))
    }

    @Test
    fun `an empty backup still needs the headroom`() {
        assertTrue(RestoreStaging.fits(0L, RestoreStaging.HEADROOM_BYTES))
        assertFalse(RestoreStaging.fits(0L, RestoreStaging.HEADROOM_BYTES - 1L))
    }
}
