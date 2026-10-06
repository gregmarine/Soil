package com.symmetricalpalmtree.soil.bibleref

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseCapTest {

    private fun passages(wire: String) = ReferenceCodec.decode(wire)!!

    @Test
    fun `ten verses are within the cap and eleven are not`() {
        assertTrue(VerseCap.withinCap(passages("JHN:3:10-3:19")))
        assertFalse(VerseCap.withinCap(passages("JHN:3:10-3:20")))
    }

    @Test
    fun `the cap counts across ranges and books`() {
        assertTrue(VerseCap.withinCap(passages("JHN:3:14-3:18,PRO:3:5-3:9")))
        assertFalse(VerseCap.withinCap(passages("JHN:3:14-3:18,PRO:3:5-3:10")))
    }

    @Test
    fun `a whole chapter is refused however short`() {
        assertFalse(VerseCap.withinCap(passages("PSA:117:0-117:999")))
        assertFalse(VerseCap.withinCap(passages("JHN:3:16-3:16,PSA:117:0-117:999")))
    }

    @Test
    fun `a cross-chapter range counts its last chapter's verses plus one, and the rows decide`() {
        assertTrue(VerseCap.withinCap(passages("JHN:3:35-4:2")))
        assertFalse(VerseCap.withinCap(passages("JHN:3:35-4:10")))
        assertFalse(VerseCap.rowsWithin(11))
        assertTrue(VerseCap.rowsWithin(10))
        assertFalse(VerseCap.rowsWithin(0))
    }
}
