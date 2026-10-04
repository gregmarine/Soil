package com.symmetricalpalmtree.soil.restore

import com.symmetricalpalmtree.soil.backup.BackupConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4's table — decision 3 made mechanical: the restored row keeps everything except the
 * destination, which is always this device's (or nothing), and every stamp and last-run figure
 * is cleared because this device has never backed up this library.
 */
class RestoreDestinationTest {

    /** A backup row as the SOURCE device wrote it — foreign in every destination field. */
    private val foreign = BackupConfig(
        treeUri = "content://com.android.externalstorage.documents/tree/BOOX%3ABackups",
        lastRunAt = 1_700_000_000_000L,
        lastCopied = 12,
        lastSkipped = 3,
        stamps = mapOf("nb-1" to 10L, "nb-2" to 20L),
        cloudEnabled = true,
        cloudDeviceFolder = "BOOX Note Air 3",
        cloudStamps = mapOf("nb-1" to 10L),
        cloudLastRunAt = 1_700_000_100_000L,
        cloudLastCopied = 11,
        cloudLastSkipped = 4,
    )

    @Test
    fun `this device's destination replaces the backup's`() {
        val parked = RestoreDestination.Parked(
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ANotesprout",
            cloudEnabled = false,
            cloudDeviceFolder = "Supernote Nomad",
        )
        val merged = RestoreDestination.merge(foreign, parked)
        assertEquals(parked.treeUri, merged.treeUri)
        assertFalse(merged.cloudEnabled)
        assertEquals("Supernote Nomad", merged.cloudDeviceFolder)
    }

    @Test
    fun `no park at all - the backup's destination is still discarded`() {
        val merged = RestoreDestination.merge(foreign, null)
        assertNull(merged.treeUri)
        assertFalse(merged.cloudEnabled)
        assertNull(merged.cloudDeviceFolder)
    }

    @Test
    fun `an empty park - same as no park`() {
        assertEquals(RestoreDestination.merge(foreign, null), RestoreDestination.merge(foreign, RestoreDestination.Parked()))
    }

    @Test
    fun `both stamp maps and every last-run figure are cleared`() {
        val merged = RestoreDestination.merge(foreign, RestoreDestination.Parked(treeUri = "x", cloudEnabled = true, cloudDeviceFolder = "y"))
        assertTrue(merged.stamps.isEmpty())
        assertTrue(merged.cloudStamps.isEmpty())
        assertNull(merged.lastRunAt)
        assertNull(merged.lastCopied)
        assertNull(merged.lastSkipped)
        assertNull(merged.cloudLastRunAt)
        assertNull(merged.cloudLastCopied)
        assertNull(merged.cloudLastSkipped)
    }

    @Test
    fun `everything else is the restored value`() {
        val merged = RestoreDestination.merge(foreign, null)
        assertEquals(foreign.version, merged.version)
    }

    @Test
    fun `idempotent - merging the result again with the same park changes nothing`() {
        val parked = RestoreDestination.Parked(treeUri = "t", cloudEnabled = true, cloudDeviceFolder = "f")
        val once = RestoreDestination.merge(foreign, parked)
        assertEquals(once, RestoreDestination.merge(once, parked))
    }

    @Test
    fun `parkedFrom lifts exactly the three destination fields`() {
        val parked = RestoreDestination.parkedFrom(foreign)
        assertEquals(foreign.treeUri, parked.treeUri)
        assertEquals(foreign.cloudEnabled, parked.cloudEnabled)
        assertEquals(foreign.cloudDeviceFolder, parked.cloudDeviceFolder)
    }

    @Test
    fun `a park round-trips through its JSON`() {
        val parked = RestoreDestination.Parked(treeUri = "content://x/y%20z", cloudEnabled = true, cloudDeviceFolder = "Nomad #2")
        val json = kotlinx.serialization.json.Json.encodeToString(RestoreDestination.Parked.serializer(), parked)
        assertEquals(parked, kotlinx.serialization.json.Json.decodeFromString(RestoreDestination.Parked.serializer(), json))
    }
}
