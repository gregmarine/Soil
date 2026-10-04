package com.symmetricalpalmtree.soil.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 in full (arc 27 / L1): what a restore takes out of a backup folder, decided from the listing
 * alone. A backup folder is whatever the writer left plus whatever a killed run stranded, so most
 * of this file is about what is *refused* — a `.part`, a `.old`, an arc-26 rekey's `.rekey.tmp` /
 * `.old.bak`, a `-shm`, a stem that is not a name this app writes. The other half is the WAL rule:
 * never a sidecar without its main, and never a sidecar at all on the cloud leg (R3).
 */
class RestoreManifestTest {

    private fun file(name: String, size: Long = 10L, modifiedAt: Long = 0L) =
        Listed(name, size, isDir = false, modifiedAt = modifiedAt)

    private fun dir(name: String) = Listed(name, -1L, isDir = true, modifiedAt = 0L)

    private val uuidA = "11111111-1111-1111-1111-111111111111"
    private val uuidB = "22222222-2222-2222-2222-222222222222"
    private val store = "com.symmetricalpalmtree.notesproutsn.ext.tags"

    private fun names(entries: List<Listed>, leg: RestoreLeg = RestoreLeg.LOCAL): List<String> =
        RestoreManifest.plan(entries, leg)!!.items.map { it.name }

    // ── isBackup: only the index decides ─────────────────────────────────────

    @Test
    fun `a folder with the index is a backup`() =
        assertTrue(RestoreManifest.isBackup(listOf(file("soil.db"))))

    @Test
    fun `an index wal alone is not a backup`() {
        val entries = listOf(file("soil.db-wal"), file("$uuidA.soil"))
        assertFalse(RestoreManifest.isBackup(entries))
        assertNull(RestoreManifest.plan(entries, RestoreLeg.LOCAL))
    }

    @Test
    fun `a directory named like the index is not a backup`() =
        assertFalse(RestoreManifest.isBackup(listOf(dir("soil.db"))))

    // ── Refused shapes ───────────────────────────────────────────────────────

    @Test
    fun `killed writer swap leftovers are never taken`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("$uuidA.soil.part"),
                file("$uuidA.soil.old"),
                file("soil.db.part"),
                file("soil.db.old"),
            )
        )
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `interrupted rekey leftovers are never taken`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("$uuidA.soil.rekey.tmp"),
                file("$uuidA.soil.old.bak"),
                file("$store.db.rekey.tmp"),
                file("$store.db.old.bak"),
            )
        )
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `shm and journal sidecars are never taken`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("soil.db-shm"),
                file("soil.db-journal"),
                file("$uuidA.soil"),
                file("$uuidA.soil-shm"),
                file("$uuidA.soil-journal"),
            )
        )
        assertEquals(listOf("soil.db", "$uuidA.soil"), taken)
    }

    @Test
    fun `a directory named like a notebook is never taken`() {
        val taken = names(listOf(file("soil.db"), dir("$uuidA.soil")))
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `an import in flight is never taken`() {
        val taken = names(listOf(file("soil.db"), file("$uuidA.soil.importing")))
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `a soil stem with a space is not a notebook`() {
        val taken = names(listOf(file("soil.db"), file("my notebook.soil")))
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `a db whose stem fails isValidExtensionPackage is not a store`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("bad name.db"),
                file("../x.db"),
                file(".db"),
            )
        )
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `the index is never read as a store`() {
        val manifest = RestoreManifest.plan(listOf(file("soil.db")), RestoreLeg.LOCAL)!!
        assertEquals(ItemKind.INDEX, manifest.items.single().kind)
        assertEquals(0, manifest.storeCount)
    }

    @Test
    fun `unrecognised files are left where they lie`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("readme.txt"),
                file("soil.db.zip"),
                file("$uuidA.soil.bak"),
            )
        )
        assertEquals(listOf("soil.db"), taken)
    }

    // ── The WAL rule, local leg ──────────────────────────────────────────────

    @Test
    fun `a wal with no main file is dropped`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("$uuidA.soil-wal"),
                file("$store.db-wal"),
            )
        )
        assertEquals(listOf("soil.db"), taken)
    }

    @Test
    fun `a main file with no wal is fine`() {
        val taken = names(listOf(file("soil.db"), file("$uuidA.soil"), file("$store.db")))
        assertEquals(listOf("soil.db", "$uuidA.soil", "$store.db"), taken)
    }

    @Test
    fun `both present means both taken on the local leg`() {
        val taken = names(
            listOf(
                file("soil.db"),
                file("soil.db-wal"),
                file("$uuidA.soil"),
                file("$uuidA.soil-wal"),
                file("$store.db"),
                file("$store.db-wal"),
            )
        )
        assertEquals(
            listOf(
                "soil.db", "soil.db-wal",
                "$uuidA.soil", "$uuidA.soil-wal",
                "$store.db", "$store.db-wal",
            ),
            taken,
        )
    }

    // ── The WAL rule, cloud leg (R3) ─────────────────────────────────────────

    @Test
    fun `the cloud leg drops every wal including the index's`() {
        val entries = listOf(
            file("soil.db"),
            file("soil.db-wal"),
            file("$uuidA.soil"),
            file("$uuidA.soil-wal"),
            file("$store.db"),
            file("$store.db-wal"),
        )
        assertEquals(
            listOf("soil.db", "$uuidA.soil", "$store.db"),
            names(entries, RestoreLeg.CLOUD),
        )
        // The same folder on the local leg takes all six — the leg is the only difference.
        assertEquals(6, names(entries, RestoreLeg.LOCAL).size)
    }

    @Test
    fun `a dropped cloud wal is neither counted nor weighed`() {
        val entries = listOf(
            file("soil.db", size = 100L),
            file("soil.db-wal", size = 4_000L),
            file("$uuidA.soil", size = 20L),
            file("$uuidA.soil-wal", size = 8_000L),
            file("$store.db", size = 7L),
            file("$store.db-wal", size = 900L),
        )
        val cloud = RestoreManifest.plan(entries, RestoreLeg.CLOUD)!!
        assertEquals(1, cloud.itemCount)
        assertEquals(1, cloud.storeCount)
        // 127, not 13 027 — the free-space gate must not pay for bytes the fetch will never take.
        assertEquals(127L, cloud.totalBytes)

        val local = RestoreManifest.plan(entries, RestoreLeg.LOCAL)!!
        assertEquals(13_027L, local.totalBytes)
    }

    // ── Order, paths and the counts the chooser shows ────────────────────────

    @Test
    fun `order is index then notebooks then stores each main before its wal`() {
        val shuffled = listOf(
            file("$store.db-wal"),
            file("$uuidB.soil"),
            file("$store.db"),
            file("soil.db-wal"),
            file("$uuidA.soil-wal"),
            file("$uuidA.soil"),
            file("soil.db"),
        )
        assertEquals(
            listOf(
                "soil.db", "soil.db-wal",
                "$uuidA.soil", "$uuidA.soil-wal",
                "$uuidB.soil",
                "$store.db", "$store.db-wal",
            ),
            names(shuffled),
        )
        // Deterministic: a different listing order gives the same plan.
        assertEquals(names(shuffled), names(shuffled.reversed()))
    }

    @Test
    fun `relative paths mirror the live layout`() {
        val manifest = RestoreManifest.plan(
            listOf(
                file("soil.db"),
                file("soil.db-wal"),
                file("$uuidA.soil"),
                file("$uuidA.soil-wal"),
                file("$store.db"),
                file("$store.db-wal"),
            ),
            RestoreLeg.LOCAL,
        )!!
        assertEquals(
            listOf(
                "soil.db", "soil.db-wal",
                "garden/$uuidA.soil", "garden/$uuidA.soil-wal",
                "garden/$store.db", "garden/$store.db-wal",
            ),
            manifest.items.map { it.relativePath },
        )
        assertEquals(
            listOf(
                ItemKind.INDEX, ItemKind.INDEX_WAL,
                ItemKind.SOIL, ItemKind.SOIL_WAL,
                ItemKind.STORE, ItemKind.STORE_WAL,
            ),
            manifest.items.map { it.kind },
        )
        assertEquals("soil.db", manifest.index.name)
    }

    @Test
    fun `counts are of main files only`() {
        val manifest = RestoreManifest.plan(
            listOf(
                file("soil.db"),
                file("$uuidA.soil"),
                file("$uuidA.soil-wal"),
                file("$uuidB.soil"),
                file("$store.db"),
            ),
            RestoreLeg.LOCAL,
        )!!
        assertEquals(2, manifest.itemCount)
        assertEquals(1, manifest.storeCount)
    }

    @Test
    fun `an unreported size counts as zero and is flagged`() {
        val manifest = RestoreManifest.plan(
            listOf(
                file("soil.db", size = 100L),
                file("$uuidA.soil", size = -1L),
                file("$uuidB.soil", size = 25L),
            ),
            RestoreLeg.LOCAL,
        )!!
        assertEquals(125L, manifest.totalBytes)
        assertTrue(manifest.hasUnknownSizes)
    }

    @Test
    fun `all sizes known is not flagged`() {
        val manifest = RestoreManifest.plan(listOf(file("soil.db", size = 4L)), RestoreLeg.LOCAL)!!
        assertEquals(4L, manifest.totalBytes)
        assertFalse(manifest.hasUnknownSizes)
    }
}
