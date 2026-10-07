package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalAddressTest {

    @Test
    fun `a day address is the scheme and exactly one ISO day`() {
        val a = CalAddress.decode("cal:2026-10-06")!!
        assertEquals("2026-10-06", a.date)
        assertEquals("cal:2026-10-06", a.encode())
        assertTrue(CalAddress.isCal("cal:anything"))
        assertFalse(CalAddress.isCal("bible:JHN:3:16"))
        assertEquals(a, CalAddress.decode(a.encode()))
    }

    @Test
    fun `anything that is not exactly the shape is null`() {
        assertNull(CalAddress.decode("cal:"))
        assertNull(CalAddress.decode("cal:2026-10-6"))
        assertNull(CalAddress.decode("cal:2026-10-06T10:00"))
        assertNull(CalAddress.decode("cal:2026-10-06 "))
        assertNull(CalAddress.decode("cal:2026-13-01"))
        assertNull(CalAddress.decode("cal:2026-02-30"))
        assertNull(CalAddress.decode("cal:2026/10/06"))
        assertNull(CalAddress.decode("CAL:2026-10-06"))
        assertNull(CalAddress.decode("soil:2026-10-06"))
        assertNull(CalAddress.decode("cal:20261006xx"))
        assertThrows(IllegalArgumentException::class.java) { CalAddress("nonsense") }
    }

    @Test
    fun `isDate is the shape alone`() {
        assertTrue(CalAddress.isDate("2024-02-29"))
        assertFalse(CalAddress.isDate("2023-02-29"))
        assertFalse(CalAddress.isDate("0000-00-00"))
        assertFalse(CalAddress.isDate(""))
    }
}
