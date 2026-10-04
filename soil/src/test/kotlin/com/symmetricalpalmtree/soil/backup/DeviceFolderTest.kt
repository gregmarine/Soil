package com.symmetricalpalmtree.soil.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFolderTest {

    @Test fun `a model name is cleaned to the narrow charset`() {
        assertEquals("Supernote-Nomad", DeviceFolder.suggestion("Supernote Nomad"))
        assertEquals("A6-X2", DeviceFolder.suggestion("A6 X2"))
        assertEquals("ab-c", DeviceFolder.suggestion("  ab/c "))
    }

    @Test fun `an unnameable model falls back`() {
        assertEquals(DeviceFolder.FALLBACK, DeviceFolder.suggestion(null))
        assertEquals(DeviceFolder.FALLBACK, DeviceFolder.suggestion("///"))
    }

    @Test fun `a long model is capped`() {
        assertEquals(DeviceFolder.MAX_MODEL_CHARS, DeviceFolder.suggestion("x".repeat(200)).length)
    }
}
