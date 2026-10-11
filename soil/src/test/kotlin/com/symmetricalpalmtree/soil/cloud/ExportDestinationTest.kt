package com.symmetricalpalmtree.soil.cloud

import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.export.ExportDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Destination row's rules (arc 25 / V3). Three questions, and the one that matters most is the
 * middle one: a *cloud* answer must never outlive the row that asked for it.
 */
class ExportDestinationTest {

    private fun status(connected: Boolean, configured: Boolean, name: String = "Google Drive") =
        CloudStatus(connected, configured, if (connected) "person@example.com" else "", name)

    @Test
    fun `the row exists only while a provider is installed`() {
        assertTrue(ExportDestination.rowVisible(providerInstalled = true))
        assertFalse(ExportDestination.rowVisible(providerInstalled = false))
    }

    @Test
    fun `a standing cloud answer is forced back to local when the row goes`() {
        assertEquals(
            ExportDestination.Choice.LOCAL,
            ExportDestination.settled(ExportDestination.Choice.CLOUD, rowVisible = false),
        )
        assertEquals(
            ExportDestination.Choice.CLOUD,
            ExportDestination.settled(ExportDestination.Choice.CLOUD, rowVisible = true),
        )
    }

    @Test
    fun `local survives either way — there is nothing to force it back to`() {
        assertEquals(
            ExportDestination.Choice.LOCAL,
            ExportDestination.settled(ExportDestination.Choice.LOCAL, rowVisible = true),
        )
        assertEquals(
            ExportDestination.Choice.LOCAL,
            ExportDestination.settled(ExportDestination.Choice.LOCAL, rowVisible = false),
        )
    }

    @Test
    fun `a connected account is simply selected`() {
        assertEquals(
            ExportDestination.Tap.SELECT,
            ExportDestination.onCloudTap(status(connected = true, configured = true)),
        )
    }

    @Test
    fun `a build with no credentials says so before it says anything about an account`() {
        assertEquals(
            ExportDestination.Tap.NOT_CONFIGURED,
            ExportDestination.onCloudTap(status(connected = false, configured = false)),
        )
    }

    @Test
    fun `no account offers Connect`() {
        assertEquals(
            ExportDestination.Tap.OFFER_CONNECT,
            ExportDestination.onCloudTap(status(connected = false, configured = true)),
        )
    }

    @Test
    fun `a provider that did not answer still offers Connect — never a silent select`() {
        assertEquals(ExportDestination.Tap.OFFER_CONNECT, ExportDestination.onCloudTap(null))
    }

    @Test
    fun `the provider's own name wins, the extension label stands in`() {
        assertEquals(
            "Google Drive",
            ExportDestination.providerName(status(connected = true, configured = true), "NSE · Google Drive"),
        )
        assertEquals("NSE · Google Drive", ExportDestination.providerName(null, "NSE · Google Drive"))
    }


    // ── Remembered (2026-10-10) ──────

    @Test
    fun `the screen opens on the remembered cloud only while the account is connected`() {
        assertEquals(ExportDestination.Choice.CLOUD, ExportDestination.opening(rememberedCloud = true, status(connected = true, configured = true)))
        assertEquals(ExportDestination.Choice.LOCAL, ExportDestination.opening(rememberedCloud = true, status(connected = false, configured = true)))
        assertEquals(ExportDestination.Choice.LOCAL, ExportDestination.opening(rememberedCloud = true, status(connected = false, configured = false)))
        assertEquals(ExportDestination.Choice.LOCAL, ExportDestination.opening(rememberedCloud = true, null))
        assertEquals(ExportDestination.Choice.LOCAL, ExportDestination.opening(rememberedCloud = false, status(connected = true, configured = true)))
    }

    @Test
    fun `a folder path survives the round trip through its stored form`() {
        val path = listOf("Exports", "Notes", "2026")
        assertEquals(path, ExportDestination.decodeFolder(ExportDestination.encodeFolder(path)))
        assertEquals("Exports/Notes/2026", ExportDestination.encodeFolder(path))
    }

    @Test
    fun `nothing remembered, or nothing usable, is the Exports folder`() {
        assertEquals(listOf("Exports"), ExportDestination.decodeFolder(null))
        assertEquals(listOf("Exports"), ExportDestination.decodeFolder(""))
        assertEquals(listOf("Exports"), ExportDestination.decodeFolder("Backups/Nomad"))
        assertEquals(listOf("Exports"), ExportDestination.decodeFolder("Exports//Notes"))
        assertEquals(listOf("Exports"), ExportDestination.decodeFolder((1..9).joinToString("/") { if (it == 1) "Exports" else "d$it" }))
        assertEquals(listOf("Exports", "Notes"), ExportDestination.decodeFolder("Exports/Notes"))
    }

    @Test
    fun `the row shows the whole path`() {
        assertEquals("Exports › Notes › 2026", ExportDestination.folderLabel(listOf("Exports", "Notes", "2026"), " › "))
        assertEquals("Exports", ExportDestination.folderLabel(listOf("Exports"), " › "))
    }

    @Test
    fun `Exports itself is never checked, a deeper folder is judged on its parent's listing`() {
        assertFalse(ExportDestination.folderNeedsCheck(listOf("Exports")))
        assertTrue(ExportDestination.folderNeedsCheck(listOf("Exports", "Notes")))
        val parent = listOf(CloudEntry("f1", "Notes", true, 0L, 0L), CloudEntry("x1", "Notes.pdf", false, 10L, 0L))
        assertTrue(ExportDestination.folderStillThere(listOf("Exports", "Notes"), parent))
        assertFalse(ExportDestination.folderStillThere(listOf("Exports", "Sketches"), parent))
        // A file of the name is not the folder.
        assertFalse(ExportDestination.folderStillThere(listOf("Exports", "Notes.pdf"), parent))
        assertFalse(ExportDestination.folderStillThere(listOf("Exports", "Notes"), emptyList()))
    }
}
