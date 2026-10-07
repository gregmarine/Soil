package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.bibleref.VerseKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file's mirror rows are untrusted input: only a row this build can read is indexed. */
class LinkRowsTest {

    private val item = "855fe3bc-1dde-4d80-b0fc-619ce65af8bc"
    private val john = VerseKey.encode(43, 3, 14) to VerseKey.encode(43, 3, 18)
    private val proverbs = VerseKey.encode(20, 3, 5) to VerseKey.encode(20, 3, 6)
    private val wire = "JHN:3:14-3:18,PRO:3:5-3:6"

    @Test
    fun `a link to an item is sound as it always was`() {
        assertTrue(LinkRows.sound(LinkRow("l", "", item, null)))
        assertTrue(LinkRows.sound(LinkRow("l", "p", item, "q")))
        assertFalse(LinkRows.sound(LinkRow("", "p", item, null)))
        assertFalse(LinkRows.sound(LinkRow("l", "p", "", null)))
    }

    @Test
    fun `a link to a day of the calendar names a day and nothing else`() {
        assertTrue(LinkRows.sound(LinkRow("l", "p", "", null, calDate = "2026-10-06")))
        assertTrue(LinkRows.sound(LinkRow("l", "", "", null, calDate = "2024-02-29")))
        assertFalse(LinkRows.sound(LinkRow("l", "p", "", null, calDate = "2023-02-29")))
        assertFalse(LinkRows.sound(LinkRow("l", "p", "", null, calDate = "")))
        assertFalse(LinkRows.sound(LinkRow("l", "p", "", null, calDate = "2026-10-06T00:00")))
        assertFalse(LinkRows.sound(LinkRow("l", "p", item, null, calDate = "2026-10-06")))
        assertFalse(LinkRows.sound(LinkRow("l", "p", "", "q", calDate = "2026-10-06")))
        assertFalse(LinkRows.sound(LinkRow("l", "p", "", null, wire, john.first, john.second, calDate = "2026-10-06")))
        assertFalse(LinkRows.sound(LinkRow("", "p", "", null, calDate = "2026-10-06")))
        assertTrue(LinkRow("l", "p", "", null, calDate = "2026-10-06").isCal)
        assertFalse(LinkRow("l", "p", item, null).isCal)
    }

    @Test
    fun `a link into the Bible names one of its wire's own ranges and no item`() {
        assertTrue(LinkRows.sound(LinkRow("l", "", "", null, wire, john.first, john.second)))
        assertTrue(LinkRows.sound(LinkRow("l#1", "", "", null, wire, proverbs.first, proverbs.second)))
        // The envelope of two ranges is not a range of the wire.
        assertFalse(LinkRows.sound(LinkRow("l", "", "", null, wire, proverbs.first, john.second)))
        assertFalse(LinkRows.sound(LinkRow("l", "", "", null, wire, john.first, john.second + 1)))
        assertFalse(LinkRows.sound(LinkRow("l", "", item, null, wire, john.first, john.second)))
        assertFalse(LinkRows.sound(LinkRow("l", "", "", "q", wire, john.first, john.second)))
        assertFalse(LinkRows.sound(LinkRow("l", "", "", null, wire, null, john.second)))
        assertFalse(LinkRows.sound(LinkRow("l", "", "", null, "not a wire", john.first, john.second)))
        assertFalse(LinkRows.sound(LinkRow("l", "", "", null, "jhn:3:14-3:18", john.first, john.second)))
        // An item link that carries a span is a row no writer makes.
        assertFalse(LinkRows.sound(LinkRow("l", "", item, null, null, john.first, john.second)))
    }

    @Test
    fun `indexable keeps the sound rows in order`() {
        val rows = listOf(
            LinkRow("a", "", item, null),
            LinkRow("b", "", "", null, "nonsense", 1, 2),
            LinkRow("c", "", "", null, wire, john.first, john.second),
        )
        assertEquals(listOf("a", "c"), LinkRows.indexable(rows).map { it.id })
    }
}
