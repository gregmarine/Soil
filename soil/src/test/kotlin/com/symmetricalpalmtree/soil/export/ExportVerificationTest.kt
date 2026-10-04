package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportVerificationTest {
    @Test fun `a file export must stream every byte`() {
        assertEquals(ExportVerification.Verdict.OK, ExportVerification.verdict(ExportContract.SOURCE_FILE, 10, 10, listOf(10)))
        assertEquals(ExportVerification.Verdict.SHORT, ExportVerification.verdict(ExportContract.SOURCE_FILE, 9, 10, listOf(9)))
        assertEquals(ExportVerification.Verdict.UNCONFIRMED, ExportVerification.verdict(ExportContract.SOURCE_FILE, 10, 10, listOf(4, 5)))
        assertEquals(ExportVerification.Verdict.OK, ExportVerification.verdict(ExportContract.SOURCE_FILE, 10, 10, emptyList()))
    }
    @Test fun `a pages export is a transform, checked against the destination only`() {
        assertEquals(ExportVerification.Verdict.OK, ExportVerification.verdict(ExportContract.SOURCE_PAGES, 7, 100, listOf(7)))
        assertEquals(ExportVerification.Verdict.SHORT, ExportVerification.verdict(ExportContract.SOURCE_PAGES, 0, 100, listOf(0)))
        assertEquals(ExportVerification.Verdict.UNCONFIRMED, ExportVerification.verdict(ExportContract.SOURCE_PAGES, 7, 100, listOf(8)))
    }
    @Test fun `an unknown kind is short`() = assertEquals(ExportVerification.Verdict.SHORT, ExportVerification.verdict(9, 1, 1, listOf(1L)))
}
