package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
