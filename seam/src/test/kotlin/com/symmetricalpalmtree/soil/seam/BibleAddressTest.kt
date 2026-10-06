package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BibleAddressTest {

    private val wire = "JHN:3:14-3:18,PRO:3:5-3:6"

    @Test
    fun `a wire reads back as written`() {
        assertEquals("bible:$wire", BibleAddress(wire).encode())
        assertEquals(BibleAddress(wire), BibleAddress.decode("bible:$wire"))
        assertTrue(BibleAddress.isBible("bible:$wire"))
        assertFalse(BibleAddress.isBible("soil:abc"))
    }

    @Test
    fun `anything that is not exactly an address is not one`() {
        assertNull(BibleAddress.decode("http://example.com"))
        assertNull(BibleAddress.decode("bible:"))
        assertNull(BibleAddress.decode("bible:jhn:3:16-3:16"))
        assertNull(BibleAddress.decode("bible:JHN:3:16-3:16 "))
        assertNull(BibleAddress.decode("bible:JHN|3"))
        assertNull(BibleAddress.decode("bible:" + "J".repeat(513)))
        assertNull(BibleAddress.decode("BIBLE:$wire"))
    }

    @Test
    fun `a wire that could not be written is refused`() {
        assertThrows(IllegalArgumentException::class.java) { BibleAddress("") }
        assertThrows(IllegalArgumentException::class.java) { BibleAddress("John 3:16") }
    }
}
