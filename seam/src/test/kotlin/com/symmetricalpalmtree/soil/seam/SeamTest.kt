package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SeamTest {
    @Test
    fun `the permission is named after the install`() {
        assertEquals(
            "com.symmetricalpalmtree.soil.permission.SEAM",
            Seam.permissionFor(Seam.HUB_PACKAGE),
        )
        assertEquals(
            "com.symmetricalpalmtree.soil.dev.permission.SEAM",
            Seam.permissionFor(Seam.HUB_PACKAGE + Seam.DEV_SUFFIX),
        )
    }

    @Test
    fun `a debug and a release Soil never share a permission`() {
        assertNotEquals(
            Seam.permissionFor(Seam.HUB_PACKAGE),
            Seam.permissionFor(Seam.HUB_PACKAGE + Seam.DEV_SUFFIX),
        )
    }

    @Test
    fun `a debug Soil opens debug apps only`() {
        assertTrue(Seam.sameBuild("com.symmetricalpalmtree.soil.dev", "com.symmetricalpalmtree.soil.notesprout.dev"))
        assertTrue(Seam.sameBuild("com.symmetricalpalmtree.soil", "com.symmetricalpalmtree.soil.notesprout"))
        assertFalse(Seam.sameBuild("com.symmetricalpalmtree.soil.dev", "com.symmetricalpalmtree.soil.notesprout"))
        assertFalse(Seam.sameBuild("com.symmetricalpalmtree.soil", "com.symmetricalpalmtree.soil.notesprout.dev"))
    }
}
