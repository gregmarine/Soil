package com.symmetricalpalmtree.soil.paper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTonesTest {

    @Test
    fun `sixteen levels, black first and white last`() {
        assertEquals(16, InkTones.LEVELS.size)
        assertEquals(InkColorCodec.BLACK, InkTones.tone(InkTones.BLACK))
        assertEquals(0xFFFFFFFF.toInt(), InkTones.tone(InkTones.WHITE))
        for (i in 1 until InkTones.TONES.size) assertTrue(InkTones.TONES[i] and 0xFF > InkTones.TONES[i - 1] and 0xFF)
    }

    @Test
    fun `a level this build does not offer reads as the fallback`() {
        assertFalse(InkTones.isLevel(200))
        assertEquals(InkColorCodec.BLACK, InkTones.tone(200))
        assertEquals(InkTones.tone(5), InkTones.tone(-1, fallback = 5))
        assertEquals(InkColorCodec.BLACK, InkTones.tone(200, fallback = 300))
        assertEquals(0, InkTones.levelOrElse(99))
        assertEquals(7, InkTones.levelOrElse(7))
    }

    @Test
    fun `four rows of four, white first`() {
        val rows = InkTones.rows()
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.size == 4 })
        assertEquals(InkTones.WHITE, rows[0][0])
        assertEquals(InkTones.BLACK, rows[3][3])
    }
}
