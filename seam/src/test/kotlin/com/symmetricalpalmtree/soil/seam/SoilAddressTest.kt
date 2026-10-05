package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SoilAddressTest {

    private val item = "855fe3bc-1dde-4d80-b0fc-619ce65af8bc"
    private val page = "061f62c3-30dc-4f08-8b89-56d0d55b0bc1"

    @Test
    fun `an item and a page of an item read back as written`() {
        assertEquals("soil:$item", SoilAddress(item).encode())
        assertEquals(SoilAddress(item), SoilAddress.decode("soil:$item"))
        assertEquals("soil:$item/$page", SoilAddress(item, page).encode())
        assertEquals(SoilAddress(item, page), SoilAddress.decode("soil:$item/$page"))
    }

    @Test
    fun `anything that is not exactly an address is not one`() {
        assertNull(SoilAddress.decode("http://example.com"))
        assertNull(SoilAddress.decode("soil:"))
        assertNull(SoilAddress.decode("soil:/x"))
        assertNull(SoilAddress.decode("soil:a/b/c"))
        assertNull(SoilAddress.decode("soil:a b"))
        assertNull(SoilAddress.decode("soil:a/../b"))
        assertNull(SoilAddress.decode("soil:" + "a".repeat(65)))
        assertNull(SoilAddress.decode("SOIL:$item"))
    }

    @Test
    fun `an id that could not be written is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SoilAddress("") }
        assertThrows(IllegalArgumentException::class.java) { SoilAddress("a/b") }
        assertThrows(IllegalArgumentException::class.java) { SoilAddress(item, "") }
    }

    @Test
    fun `an address is known by its scheme`() {
        assertTrue(SoilAddress.isSoil("soil:$item"))
        assertFalse(SoilAddress.isSoil("https://soil:x"))
    }
}
