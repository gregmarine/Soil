package com.symmetricalpalmtree.soil.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtContractTest {

    @Test
    fun `a status outside the four reads as unavailable`() {
        assertEquals(ExtContract.STATUS_READY, ExtContract.status(0))
        assertEquals(ExtContract.STATUS_UNAVAILABLE, ExtContract.status(3))
        assertEquals(ExtContract.STATUS_UNAVAILABLE, ExtContract.status(-1))
        assertEquals(ExtContract.STATUS_UNAVAILABLE, ExtContract.status(9))
    }

    @Test
    fun `declared languages are split, trimmed and bounded`() {
        assertEquals(listOf("en-US", "de-DE"), ExtContract.languages(" en-US, de-DE ,,"))
        assertTrue(ExtContract.languages(null).isEmpty())
        assertTrue(ExtContract.languages("x".repeat(40)).isEmpty())
    }

    @Test
    fun `an extension serves the build it was built for`() {
        assertTrue(ExtContract.sameBuild("com.symmetricalpalmtree.soil.dev", "com.symmetricalpalmtree.soil.ext.mlkit.dev"))
        assertTrue(ExtContract.sameBuild("com.symmetricalpalmtree.soil", "com.symmetricalpalmtree.soil.ext.mlkit"))
        assertFalse(ExtContract.sameBuild("com.symmetricalpalmtree.soil.dev", "com.symmetricalpalmtree.soil.ext.mlkit"))
    }

    @Test
    fun `an ink stroke is bare geometry of one non-zero length`() {
        val s = InkStroke(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        assertEquals(2, s.size)
        for (bad in listOf({ InkStroke(FloatArray(0), FloatArray(0)) }, { InkStroke(floatArrayOf(1f), floatArrayOf(1f, 2f)) })) {
            try { bad(); throw AssertionError("accepted") } catch (_: IllegalArgumentException) {}
        }
    }
}
