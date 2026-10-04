package com.symmetricalpalmtree.soil.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two computed budgets on the cloud seam — the numbers a caller must work out rather than read,
 * and therefore the ones worth a table: the upload rate (arc 25 / V2) and its read-side twin
 * (arc 27 / L4), which a whole-library restore scales by what the listing said.
 */
class CloudTimeoutsTest {

    private val mib = 1024L * 1024

    @Test
    fun `an empty file gets the small budget`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(0))
    }

    @Test
    fun `one byte gets the small budget`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(1))
    }

    @Test
    fun `the small ceiling itself is still small`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(5 * mib))
    }

    @Test
    fun `one byte over the ceiling is one large slice`() {
        assertEquals(CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(5 * mib + 1))
    }

    @Test
    fun `twenty mebibytes is exactly one slice`() {
        assertEquals(CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(20 * mib))
    }

    @Test
    fun `a partial slice is charged in full`() {
        assertEquals(2 * CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(20 * mib + 1))
    }

    @Test
    fun `a hundred mebibytes is five slices`() {
        assertEquals(5 * CloudTimeouts.UPLOAD_LARGE_MS, CloudTimeouts.uploadBudgetMs(100 * mib))
    }

    @Test
    fun `a nonsense byte count is charged the small budget, not an exception`() {
        assertEquals(CloudTimeouts.UPLOAD_SMALL_MS, CloudTimeouts.uploadBudgetMs(-1))
    }

    @Test
    fun `the budget never decreases as the file grows`() {
        var previous = 0L
        var bytes = 0L
        while (bytes <= 200 * mib) {
            val budget = CloudTimeouts.uploadBudgetMs(bytes)
            assertTrue("budget shrank at $bytes B", budget >= previous)
            previous = budget
            bytes += mib
        }
    }

    // ── The download rate (arc 27 / L4) ──────────────────────────────────────

    @Test
    fun `anything up to one slice gets the flat download budget`() {
        assertEquals(CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(1))
        assertEquals(CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(5 * mib))
        assertEquals(CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(20 * mib))
    }

    @Test
    fun `an empty file gets the flat download budget`() {
        assertEquals(CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(0))
    }

    @Test
    fun `one byte over a slice is charged two`() {
        assertEquals(2 * CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(20 * mib + 1))
    }

    @Test
    fun `an exact multiple of the slice is charged no extra`() {
        assertEquals(5 * CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(100 * mib))
    }

    @Test
    fun `a nonsense byte count is charged the flat budget, not an exception`() {
        assertEquals(CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(-1))
        assertEquals(CloudTimeouts.DOWNLOAD_MS, CloudTimeouts.downloadBudgetMs(Long.MIN_VALUE))
    }

    @Test
    fun `the download budget never decreases as the file grows`() {
        var previous = 0L
        var bytes = 0L
        while (bytes <= 200 * mib) {
            val budget = CloudTimeouts.downloadBudgetMs(bytes)
            assertTrue("budget shrank at $bytes B", budget >= previous)
            previous = budget
            bytes += mib
        }
    }
}
