package com.symmetricalpalmtree.soil.seam

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamCallerCheckTest {

    @Test
    fun soilItselfIsTrusted() {
        assertTrue(SeamCallerCheck.trusted(sameUid = true, signatures = PackageManager.SIGNATURE_NO_MATCH))
    }

    @Test
    fun anAppSignedWithSoilsKeyIsTrusted() {
        assertTrue(SeamCallerCheck.trusted(sameUid = false, signatures = PackageManager.SIGNATURE_MATCH))
    }

    @Test
    fun everythingElseIsAStranger() {
        for (answer in listOf(
            PackageManager.SIGNATURE_NO_MATCH,
            PackageManager.SIGNATURE_NEITHER_SIGNED,
            PackageManager.SIGNATURE_FIRST_NOT_SIGNED,
            PackageManager.SIGNATURE_SECOND_NOT_SIGNED,
            PackageManager.SIGNATURE_UNKNOWN_PACKAGE,
        )) {
            assertFalse("answer $answer", SeamCallerCheck.trusted(sameUid = false, signatures = answer))
        }
    }

    @Test
    fun theVersionDoesNotMoveDuringDevelopment() {
        assertEquals(1, Seam.VERSION)
    }

    @Test
    fun theServiceIsNamedWhereSoilPutsIt() {
        assertEquals("com.symmetricalpalmtree.soil.seam.SoilSeamService", Seam.SERVICE_CLASS)
    }
}
