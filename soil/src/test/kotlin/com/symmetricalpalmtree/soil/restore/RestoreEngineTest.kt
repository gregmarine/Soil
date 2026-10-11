package com.symmetricalpalmtree.soil.restore

import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.restore.RestoreEngine.Problem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The engine's pure parts (L2): the two free-space gates and the validation rule. The swap itself
 * is renames against a real volume and is proved on the Nomad in L5's failure-injection pass.
 */
class RestoreEngineTest {

    private val mb = 1L shl 20
    private val headroom = RestoreStaging.HEADROOM_BYTES

    // ── Free space ───────────────────────────────────────────────────────────

    @Test
    fun `pre-fetch gate - fits when total plus headroom is within usable`() {
        assertNull(RestoreEngine.spaceProblem(totalBytes = 100 * mb, usableBytes = 100 * mb + headroom))
    }

    @Test
    fun `pre-fetch gate - one byte short names the shortfall`() {
        val p = RestoreEngine.spaceProblem(totalBytes = 100 * mb, usableBytes = 100 * mb + headroom - 1)
        assertEquals(Problem.NotEnoughSpace(1L), p)
    }

    @Test
    fun `pre-fetch gate - shortfall is what is missing, not what was asked`() {
        val p = RestoreEngine.spaceProblem(totalBytes = 500 * mb, usableBytes = 200 * mb)
        assertEquals(Problem.NotEnoughSpace(300 * mb + headroom), p)
    }

    @Test
    fun `pre-fetch gate - unknown usable refuses`() {
        val p = RestoreEngine.spaceProblem(totalBytes = 1 * mb, usableBytes = -1L)
        assertTrue(p is Problem.NotEnoughSpace)
    }

    @Test
    fun `pre-fetch gate - unknown total refuses`() {
        assertTrue(RestoreEngine.spaceProblem(totalBytes = -1L, usableBytes = 10_000 * mb) is Problem.NotEnoughSpace)
    }

    @Test
    fun `post-stage gate - only the headroom must still fit`() {
        assertNull(RestoreEngine.headroomProblem(usableBytes = headroom))
        assertEquals(Problem.NotEnoughSpace(1L), RestoreEngine.headroomProblem(usableBytes = headroom - 1))
    }

    @Test
    fun `headroom is og's 64 MB`() {
        assertEquals(64L shl 20, headroom)
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private lateinit var staging: File

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("restore-engine-test").toFile()
        File(staging, "garden").mkdirs()
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
    }

    private fun stage(item: Item, bytes: Int = item.size.toInt()) {
        val f = RestoreStaging.targetFor(staging, item)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    private val index = Item("soil.db", 4096, ItemKind.INDEX, "soil.db")
    private val indexWal = Item("soil.db-wal", 100, ItemKind.INDEX_WAL, "soil.db-wal")
    private val soil = Item("a1.soil", 8192, ItemKind.SOIL, "garden/a1.soil")
    private val soilWal = Item("a1.soil-wal", 50, ItemKind.SOIL_WAL, "garden/a1.soil-wal")
    private val store = Item("com.x.ext.db", 2048, ItemKind.STORE, "garden/com.x.ext.db")

    private val allEncrypted: (File) -> SoilFileKind = { SoilFileKind.Encrypted }

    @Test
    fun `every main file encrypted and every file present - passes`() {
        val manifest = RestoreManifest(listOf(index, indexWal, soil, soilWal, store))
        manifest.items.forEach { stage(it) }
        assertNull(RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `an Invalid notebook fails the whole restore by name`() {
        val manifest = RestoreManifest(listOf(index, soil, store))
        manifest.items.forEach { stage(it) }
        val probe: (File) -> SoilFileKind = { if (it.name == "a1.soil") SoilFileKind.Invalid else SoilFileKind.Encrypted }
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest, probe))
    }

    @Test
    fun `a Plaintext file is refused too - SN has no plaintext mode`() {
        val manifest = RestoreManifest(listOf(index, store))
        manifest.items.forEach { stage(it) }
        val probe: (File) -> SoilFileKind = { if (it.name == "com.x.ext.db") SoilFileKind.Plaintext else SoilFileKind.Encrypted }
        assertEquals(Problem.InvalidFile("com.x.ext.db"), RestoreEngine.validationProblem(staging, manifest, probe))
    }

    @Test
    fun `a Plaintext index is refused`() {
        val manifest = RestoreManifest(listOf(index))
        stage(index)
        assertEquals(Problem.InvalidFile("soil.db"), RestoreEngine.validationProblem(staging, manifest, probe = { SoilFileKind.Plaintext }))
    }

    @Test
    fun `a missing staged file fails by name - WAL included`() {
        val manifest = RestoreManifest(listOf(index, soil, soilWal))
        stage(index); stage(soil) // the WAL never landed
        assertEquals(Problem.InvalidFile("a1.soil-wal"), RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `a short staged file fails by name`() {
        val manifest = RestoreManifest(listOf(index, soil))
        stage(index); stage(soil, bytes = 8000)
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `an unreported size is not held against the file`() {
        val unsized = soil.copy(size = -1L)
        val manifest = RestoreManifest(listOf(index, unsized))
        stage(index); stage(unsized, bytes = 123)
        assertNull(RestoreEngine.validationProblem(staging, manifest, allEncrypted))
    }

    @Test
    fun `WAL files are never probed`() {
        val manifest = RestoreManifest(listOf(index, indexWal, soil, soilWal))
        manifest.items.forEach { stage(it) }
        val probed = ArrayList<String>()
        assertNull(RestoreEngine.validationProblem(staging, manifest, probe = { probed += it.name; SoilFileKind.Encrypted }))
        assertEquals(listOf("soil.db", "a1.soil"), probed)
    }

    @Test
    fun `the first bad file is the one named, in manifest order`() {
        val manifest = RestoreManifest(listOf(index, soil, store))
        manifest.items.forEach { stage(it) }
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest, probe = { if (it.name == "soil.db") SoilFileKind.Encrypted else SoilFileKind.Invalid }))
    }

    @Test
    fun `the RESTORE limiter bucket is its own`() {
        assertEquals("RESTORE", RestoreEngine.LIMITER_KEY)
    }

    // ── Validation by kind (L5) ──────────────────────────────────────────────

    @Test
    fun `only - a plaintext notebook or store is not probed when only the index is asked`() {
        val manifest = RestoreManifest(listOf(index, soil, store))
        manifest.items.forEach { stage(it) }
        val probe: (File) -> SoilFileKind = { if (it.name == "soil.db") SoilFileKind.Encrypted else SoilFileKind.Plaintext }
        assertNull(RestoreEngine.validationProblem(staging, manifest, probe, RestoreEngine.INDEX_ONLY))
        assertEquals(Problem.InvalidFile("a1.soil"), RestoreEngine.validationProblem(staging, manifest, probe, RestoreEngine.ITEMS))
    }

    @Test
    fun `only - a missing notebook WAL is not held against the index pass`() {
        val manifest = RestoreManifest(listOf(index, soil, soilWal))
        stage(index); stage(soil)
        assertNull(RestoreEngine.validationProblem(staging, manifest, allEncrypted, RestoreEngine.INDEX_ONLY))
        assertEquals(Problem.InvalidFile("a1.soil-wal"), RestoreEngine.validationProblem(staging, manifest, allEncrypted, RestoreEngine.ITEMS))
    }

    @Test
    fun `only - the three parts together are the whole`() {
        val stores = setOf(ItemKind.STORE, ItemKind.STORE_WAL)
        assertEquals(RestoreEngine.ALL_KINDS, RestoreEngine.INDEX_ONLY + RestoreEngine.ITEMS + stores)
        assertTrue((RestoreEngine.INDEX_ONLY intersect RestoreEngine.ITEMS).isEmpty())
        assertTrue((stores intersect RestoreEngine.ITEMS).isEmpty())
    }

    // ── Orphans (L5) ─────────────────────────────────────────────────────────

    private val soilB = Item("b2.soil", 100, ItemKind.SOIL, "garden/b2.soil")
    private val soilBWal = Item("b2.soil-wal", 10, ItemKind.SOIL_WAL, "garden/b2.soil-wal")

    @Test
    fun `orphans - a notebook the index names is kept, one it does not is left out with its WAL`() {
        val manifest = RestoreManifest(listOf(index, soil, soilWal, soilB, soilBWal, store))
        val (kept, leftOut) = RestoreEngine.orphanRule(manifest, setOf("a1"))
        assertEquals(listOf(index, soil, soilWal, store), kept.items)
        assertEquals(listOf("b2.soil"), leftOut)
    }

    @Test
    fun `missing - an alive item the backup should carry but does not is named, sorted`() {
        val manifest = RestoreManifest(listOf(index, soil, soilWal, store))
        assertEquals(listOf("b2.soil", "c3.soil"), RestoreEngine.missingItems(manifest, setOf("c3", "a1", "b2")))
        assertTrue(RestoreEngine.missingItems(manifest, setOf("a1")).isEmpty())
        assertTrue(RestoreEngine.missingItems(manifest, emptySet()).isEmpty())
    }

    @Test
    fun `orphans - nothing left out when every notebook is named`() {
        val manifest = RestoreManifest(listOf(index, soil, soilB))
        val (kept, leftOut) = RestoreEngine.orphanRule(manifest, setOf("a1", "b2", "c3"))
        assertEquals(manifest.items, kept.items)
        assertTrue(leftOut.isEmpty())
    }

    @Test
    fun `orphans - stores and the index are never orphans`() {
        val manifest = RestoreManifest(listOf(index, indexWal, store))
        val (kept, leftOut) = RestoreEngine.orphanRule(manifest, emptySet())
        assertEquals(manifest.items, kept.items)
        assertTrue(leftOut.isEmpty())
    }

    @Test
    fun `orphans - an empty index leaves every notebook out, named in order`() {
        val manifest = RestoreManifest(listOf(index, soilB, soil))
        val (kept, leftOut) = RestoreEngine.orphanRule(manifest, emptySet())
        assertEquals(listOf(index), kept.items)
        assertEquals(listOf("a1.soil", "b2.soil"), leftOut)
        assertEquals(0, kept.itemCount)
    }

    // ── Recovery executor over real files (L5) ───────────────────────────────

    private fun tree(): Triple<File, RestoreEngine.Live, File> {
        val root = Files.createTempDirectory("restore-recovery").toFile()
        val live = RestoreEngine.Live(File(root, "soil.db"), File(root, "garden"))
        val aside = File(root, RestoreEngine.ASIDE_DIR)
        return Triple(root, live, aside)
    }

    @Test
    fun `recovery - index and Garden aside rename back and the aside is gone`() {
        val (root, live, aside) = tree()
        try {
            File(aside, "garden").mkdirs(); File(aside, "garden/x.soil").writeBytes(ByteArray(10))
            File(aside, "soil.db").writeBytes(ByteArray(20))
            File(aside, "soil.db-wal").writeBytes(ByteArray(5))
            RestoreEngine.executeRecovery(root, live, aside, RestoreStaging.dir(root))
            assertTrue(live.index.isFile); assertTrue(File(root, "soil.db-wal").isFile)
            assertTrue(File(live.garden, "x.soil").isFile)
            assertTrue(!aside.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery - a file squatting on the Garden name is cleared before the rename back`() {
        val (root, live, aside) = tree()
        try {
            File(aside, "garden").mkdirs(); File(aside, "garden/x.soil").writeBytes(ByteArray(10))
            File(aside, "soil.db").writeBytes(ByteArray(20))
            live.garden.writeText("planted") // the PLANT_AT_C shape: 8(c) failed on this
            RestoreEngine.executeRecovery(root, live, aside, RestoreStaging.dir(root))
            assertTrue(live.garden.isDirectory)
            assertTrue(File(live.garden, "x.soil").isFile)
            assertTrue(live.index.isFile)
            assertTrue(!aside.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery - a landed index means the aside and staging are discarded whole`() {
        val (root, live, aside) = tree()
        try {
            live.index.writeBytes(ByteArray(20)); live.garden.mkdirs()
            File(aside, "garden").mkdirs(); File(aside, "soil.db").writeBytes(ByteArray(20))
            val staging = RestoreStaging.reset(root); File(staging, "left.part").writeBytes(ByteArray(1))
            RestoreEngine.executeRecovery(root, live, aside, staging)
            assertTrue(!aside.exists()); assertTrue(!staging.exists())
            assertTrue(live.index.isFile); assertTrue(live.garden.isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery - a new Garden beside an aside one is the staged one and is deleted`() {
        val (root, live, aside) = tree()
        try {
            live.garden.mkdirs(); File(live.garden, "new.soil").writeBytes(ByteArray(3))
            File(aside, "garden").mkdirs(); File(aside, "garden/old.soil").writeBytes(ByteArray(10))
            File(aside, "soil.db").writeBytes(ByteArray(20))
            RestoreEngine.executeRecovery(root, live, aside, RestoreStaging.dir(root))
            assertTrue(File(live.garden, "old.soil").isFile)
            assertTrue(!File(live.garden, "new.soil").exists())
            assertTrue(live.index.isFile)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery - a WAL the new index left at the live name is cleared before the old index returns`() {
        val (root, live, aside) = tree()
        try {
            File(aside, "garden").mkdirs()
            File(aside, "soil.db").writeBytes(ByteArray(20))
            File(root, "soil.db-wal").writeBytes(ByteArray(99)) // 8(d) landed, 8(e) did not
            File(root, "soil.db-shm").writeBytes(ByteArray(9))
            RestoreEngine.executeRecovery(root, live, aside, RestoreStaging.dir(root))
            assertTrue(live.index.isFile)
            assertTrue(!File(root, "soil.db-wal").exists())
            assertTrue(!File(root, "soil.db-shm").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `recovery - the old index's own aside sidecars come back with it`() {
        val (root, live, aside) = tree()
        try {
            aside.mkdirs()
            File(aside, "soil.db").writeBytes(ByteArray(20))
            File(aside, "soil.db-wal").writeBytes(ByteArray(7))
            File(root, "soil.db-wal").writeBytes(ByteArray(99)) // the new one, cleared first
            RestoreEngine.executeRecovery(root, live, aside, RestoreStaging.dir(root))
            assertEquals(7L, File(root, "soil.db-wal").length())
        } finally { root.deleteRecursively() }
    }

    // ── After a failed fetch (L5) ────────────────────────────────────────────

    private val sourceFail = Problem.Source(RestoreProblem.FetchFailed("x.soil"))

    @Test
    fun `fetch failure - the disk is named when the rest plus headroom no longer fits`() {
        val p = RestoreEngine.fetchFailureProblem(sourceFail, totalBytes = 100 * mb, stagedBytes = 40 * mb, usableBytes = 10 * mb)
        assertEquals(Problem.NotEnoughSpace(60 * mb + headroom - 10 * mb), p)
    }

    @Test
    fun `fetch failure - the source's problem stands when space is fine`() {
        assertEquals(sourceFail, RestoreEngine.fetchFailureProblem(sourceFail, 100 * mb, 40 * mb, usableBytes = 10_000 * mb))
    }

    @Test
    fun `fetch failure - an unknown total only asks for the headroom`() {
        assertEquals(sourceFail, RestoreEngine.fetchFailureProblem(sourceFail, -1L, 40 * mb, usableBytes = headroom))
    }

    /** Cleanup, 2026-10-10: a stop is the person's whatever the disk says of the rest; it is never reworded as the disk. */
    @Test
    fun aCancelledFetchStaysCancelled() {
        val mb = 1024L * 1024
        val cancelled = RestoreEngine.Problem.Source(RestoreProblem.Cancelled)
        assertEquals(cancelled, RestoreEngine.fetchFailureProblem(cancelled, totalBytes = 100 * mb, stagedBytes = 40 * mb, usableBytes = 10 * mb))
        assertTrue(RestoreEngine.fetchFailureProblem(sourceFail, -1L, 40 * mb, usableBytes = headroom - 1) is Problem.NotEnoughSpace)
    }

    @Test
    fun `fetch failure - an unmeasurable volume never blames the disk`() {
        assertEquals(sourceFail, RestoreEngine.fetchFailureProblem(sourceFail, 100 * mb, 0L, usableBytes = -1L))
    }

    @Test
    fun `staged bytes count every file including parts`() {
        stage(index); stage(soil)
        File(staging, "garden/b.soil.part").writeBytes(ByteArray(10))
        assertEquals(4096L + 8192L + 10L, RestoreStaging.stagedBytes(staging))
        assertEquals(0L, RestoreStaging.stagedBytes(File(staging, "missing")))
    }

    @Test
    fun `orphans - a dead store is left out with its WAL and named`() {
        val storeWal = Item("com.x.ext.db-wal", 5, ItemKind.STORE_WAL, "garden/com.x.ext.db-wal")
        val store2 = Item("com.y.ext.db", 5, ItemKind.STORE, "garden/com.y.ext.db")
        val manifest = RestoreManifest(listOf(index, soil, store, storeWal, store2))
        val (kept, leftOut) = RestoreEngine.orphanRule(manifest, setOf("a1"), deadStores = setOf("com.x.ext.db"))
        assertEquals(listOf(index, soil, store2), kept.items)
        assertEquals(listOf("com.x.ext.db"), leftOut)
        assertEquals(1, kept.storeCount)
    }
}
