package com.symmetricalpalmtree.soil.notesprout.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTreeTest {

    private fun item(id: String, page: Int, y: Float, level: Int, label: String = id) = OutlineTree.Item(id, "p$page", page, 0f, y, label, level)

    @Test
    fun `a tree in document order, orphans attached to the nearest shallower heading`() {
        val roots = OutlineTree.build(listOf(item("c", 1, 10f, 3), item("a", 0, 10f, 1), item("b", 0, 50f, 2), item("d", 2, 0f, 1)))
        assertEquals(listOf("a", "d"), roots.map { it.id })
        assertEquals(listOf("b"), roots[0].children.map { it.id })
        assertEquals(listOf("c"), roots[0].children[0].children.map { it.id })
        // An H3 with no H1 or H2 before it is a root.
        assertEquals("c", OutlineTree.build(listOf(item("c", 0, 0f, 3)))[0].id)
    }

    @Test
    fun `visible rows follow the expansion, and the highlight its ancestors`() {
        val roots = OutlineTree.build(listOf(item("a", 0, 0f, 1), item("b", 1, 0f, 2), item("c", 3, 0f, 1)))
        assertEquals(listOf("a", "c"), OutlineTree.visible(roots, emptySet()).map { it.id })
        assertEquals(listOf("a", "b", "c"), OutlineTree.visible(roots, setOf("a")).map { it.id })
        val all = OutlineTree.all(roots)
        assertEquals("b", OutlineTree.highlight(all, 2, setOf("a")))
        assertEquals("a", OutlineTree.highlight(all, 2, emptySet()))
        assertEquals("c", OutlineTree.highlight(all, 9, emptySet()))
        assertNull(OutlineTree.highlight(emptyList(), 0, emptySet()))
        assertEquals(listOf("a"), OutlineTree.ancestorsOf(roots[0].children[0]))
    }

    @Test
    fun `items resolve pages, strip prefixes, and cap`() {
        val h = Heading("h", "## Title ", 2, 0f, 0f, 1f, 1f, 0)
        val pages = mapOf("p0" to 0, "p1" to 1)
        val (items, truncated) = OutlineTree.items(listOf(h to "p1", h.copy(id = "x", text = "#   ") to "p0", h.copy(id = "gone") to "nope"), pages)
        assertEquals(listOf("h"), items.map { it.objectId })
        assertEquals("Title", items[0].label)
        assertEquals(1, items[0].pageIndex)
        assertTrue(!truncated)
        // Under a link, placed by the link's page.
        val (under, _) = OutlineTree.items(listOf(h to "link1"), pages, mapOf("link1" to "p0"))
        assertEquals(0, under[0].pageIndex)
    }

    @Test
    fun `paging math`() {
        assertEquals(1, OutlineTree.pageCount(0, 5))
        assertEquals(3, OutlineTree.pageCount(11, 5))
        assertEquals(2, OutlineTree.pageOf(11, 5))
        assertEquals(0, OutlineTree.pageOf(-1, 5))
    }
}
