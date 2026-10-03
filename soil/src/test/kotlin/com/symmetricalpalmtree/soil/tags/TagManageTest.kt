package com.symmetricalpalmtree.soil.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Manage overview: what it lists, in what order, and what a bare row says. */
class TagManageTest {

    private val nb = "11111111-1111-4111-8111-111111111111"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val p2 = "bbbbbbbb-2222-4222-8222-222222222222"

    @Test
    fun `the item comes first, then the pages in the index's order, labels verbatim`() {
        val rows = TagManage.targets(nb, "Notebook", listOf(p1, p2), listOf("Page 1", "Seite 2"))
        assertEquals(3, rows.size)
        assertNull(rows[0].pageId)
        assertEquals("Notebook", rows[0].label)
        assertEquals(listOf(p1, p2), rows.drop(1).map { it.pageId })
        assertEquals(listOf("Page 1", "Seite 2"), rows.drop(1).map { it.label })
        assertTrue(rows.all { it.itemId == nb })
    }

    @Test
    fun `no pages is one row, and mismatched arrays list what both halves have`() {
        assertEquals(1, TagManage.targets(nb, "Notebook", emptyList(), emptyList()).size)
        assertEquals(2, TagManage.targets(nb, "Notebook", listOf(p1, p2), listOf("Page 1")).size)
    }

    @Test
    fun `a row's tags read in order, or say so in words`() {
        assertEquals("2026, reading list", TagManage.summary(listOf("2026", "reading list"), "No tags", ", "))
        assertEquals("No tags", TagManage.summary(emptyList(), "No tags", ", "))
        assertEquals("recipes", TagManage.summary(listOf("recipes"), "No tags", ", "))
    }
}
