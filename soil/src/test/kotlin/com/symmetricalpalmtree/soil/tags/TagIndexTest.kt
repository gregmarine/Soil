package com.symmetricalpalmtree.soil.tags

import com.symmetricalpalmtree.soil.data.index.Assignment
import com.symmetricalpalmtree.soil.data.index.TagRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the tag screen asks over and over, per keystroke and per repaint, and never asks the store for. */
class TagIndexTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private var minted = 0

    private fun tag(display: String): TagRecord { minted++; return TagRecord("%08x-1111-4111-8111-111111111111".format(minted), display) }
    private fun index(vararg pairs: Pair<TagRecord, List<Assignment>>) = TagIndex(pairs.map { it.first }, pairs.flatMap { it.second })
    private fun on(tag: TagRecord, itemId: String, pageId: String? = null) = Assignment(tag.id, itemId, pageId ?: "")

    @Test
    fun `find is by identity, not spelling`() {
        val reading = tag("Reading List")
        val index = index(reading to emptyList())
        assertEquals(reading.id, index.find("  reading   list ")!!.id)
        assertEquals("Reading List", index.find("READING LIST")!!.display)
        assertNull(index.find("nope"))
        assertEquals("Reading List", index.tag(reading.id)!!.display)
        assertNull(index.tag(n1))
    }

    @Test
    fun `sorted tags is the browse order`() {
        val index = index(tag("zebra") to emptyList(), tag("Apple") to emptyList())
        assertEquals(listOf("Apple", "zebra"), index.sortedTags().map { it.display })
    }

    @Test
    fun `tagsOf is sorted and scoped to one target, and a page is identified by its item too`() {
        val zebra = tag("zebra"); val apple = tag("Apple"); val other = tag("other")
        val index = index(zebra to listOf(on(zebra, n1)), apple to listOf(on(apple, n1)), other to listOf(on(other, n1, p1), on(other, n2, p1)))
        assertEquals(listOf("Apple", "zebra"), index.tagsOf(n1).map { it.display })
        assertEquals(listOf("other"), index.tagsOf(n1, p1).map { it.display })
        assertEquals(listOf("other"), index.tagsOf(n2, p1).map { it.display })
        assertTrue(index.tagsOf(n2).isEmpty())
        assertTrue(index.isAssigned(zebra.id, n1))
        assertFalse(index.isAssigned(zebra.id, n1, p1))
        assertFalse(index.isAssigned(zebra.id, n2))
    }

    @Test
    fun `suggest ranks exact, then prefix, then substring, and a blank query offers everything`() {
        val index = index(tag("read") to emptyList(), tag("reading list") to emptyList(), tag("unread") to emptyList(), tag("zzz") to emptyList())
        assertEquals(listOf("read", "reading list", "unread"), index.suggest("read").map { it.display })
        assertEquals(listOf("read", "reading list", "unread", "zzz"), index.suggest("  ").map { it.display })
    }

    @Test
    fun `a repeated identity keeps the first, and empty is empty`() {
        val first = tag("Draft"); val second = tag("draft")
        val index = index(first to emptyList(), second to emptyList())
        assertEquals(first.id, index.find("DRAFT")!!.id)
        assertNotNull(index.tag(second.id))
        assertTrue(TagIndex.EMPTY.sortedTags().isEmpty())
        assertNull(TagIndex.EMPTY.find("draft"))
    }
}
