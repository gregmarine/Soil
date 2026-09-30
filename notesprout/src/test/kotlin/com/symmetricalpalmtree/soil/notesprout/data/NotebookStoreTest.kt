package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookStoreTest {

    private val rows = FakeNotebookRows()
    private val store = NotebookStore(rows, "nb")

    private fun page(id: String, vararg under: String) = FakeNotebookRows.Page(id, 100f, 200f, "", under.toList())

    @Test
    fun `a new notebook is a root row, one page and the page last open, in one transaction`() {
        val loaded = store.initialize("Journal", 100f, 200f)
        assertEquals(1, rows.execs.size)
        assertEquals(3, rows.execs[0].size)
        assertTrue(rows.sql()[0].contains("'notebook'"))
        assertTrue(rows.sql()[1].contains("'page'"))
        assertEquals(loaded.pages[0].id, loaded.currentId)
        assertEquals(100f, loaded.pages[0].width)
    }

    @Test
    fun `load lands on the page last open, or the first`() {
        rows.pages = listOf(page("a"), page("b"))
        rows.lastOpened = "b"
        assertEquals("b", store.load().currentId)
        rows.lastOpened = "gone"
        assertEquals("a", store.load().currentId)
        rows.lastOpened = null
        assertEquals("a", store.load().currentId)
    }

    @Test
    fun `a notebook with no pages is refused, not fabricated`() {
        assertThrows(StoreUnavailable::class.java) { store.load() }
        assertTrue(rows.execs.isEmpty())
    }

    @Test
    fun `an inserted page inherits the paper of the one it follows and every page is renumbered`() {
        val pages = listOf(PageRef("a", 100f, 200f, "t1"), PageRef("b", 100f, 200f, "t1"))
        val (next, page) = store.insertPage(pages, "a", after = true)
        assertEquals(listOf("a", page.id, "b"), next.map { it.id })
        assertEquals("t1", page.templateId)
        val sql = rows.sql()
        assertTrue(sql[0].startsWith("INSERT OR IGNORE"))
        assertEquals(3, sql.count { it.contains("SET \"order\"") })
        assertTrue(sql.last().contains("refId"))
    }

    @Test
    fun `a deleted page takes what is under it and lands on the page before`() {
        rows.pages = listOf(page("a"), page("b", "s1", "s2"), page("c"))
        val pages = rows.pages.map { PageRef(it.id, it.width, it.height, it.templateId) }
        val (next, landing, taken) = store.deletePage(pages, pages[1])
        assertEquals(listOf("a", "c"), next.map { it.id })
        assertEquals("a", landing.id)
        assertEquals(listOf("s1", "s2"), taken)
        assertEquals(3, rows.sql().count { it.contains("deletedAt = ?") })
    }

    @Test
    fun `deleting the only page puts a fresh one in its place`() {
        rows.pages = listOf(page("a", "s1"))
        val pages = listOf(PageRef("a", 100f, 200f, "t"))
        val (next, landing, _) = store.deletePage(pages, pages[0])
        assertEquals(1, next.size)
        assertTrue(landing.id != "a")
        assertEquals("t", landing.templateId)
        assertTrue(rows.sql().any { it.startsWith("INSERT OR IGNORE") })
    }

    @Test
    fun `erase page takes what is under the page and leaves the page`() {
        rows.pages = listOf(page("a", "s1", "s2"))
        assertEquals(listOf("s1", "s2"), store.erasePage("a"))
        val sql = rows.sql()
        assertEquals(2, sql.size)
        assertTrue(sql.all { it.contains("deletedAt = ?") })
        assertTrue(rows.statements.none { (it.args[1] as com.symmetricalpalmtree.soil.paper.store.Cell.Text).value == "a" })
        // An empty page's erase writes nothing.
        rows.pages = listOf(page("b"))
        assertTrue(store.erasePage("b").isEmpty())
        assertEquals(2, rows.sql().size)
    }

    @Test
    fun `reconcile restores in place, deletes what is not wanted, and orders the rest`() {
        val a = PageRef("a", 1f, 1f, "")
        val b = PageRef("b", 1f, 1f, "")
        store.reconcile(alive = listOf(a), target = listOf(b, a), restoreIds = listOf("s1"), deleteIds = emptyList(), currentId = "b")
        val sql = rows.sql()
        assertEquals(1, rows.execs.size)
        assertTrue(sql.any { it.startsWith("INSERT OR IGNORE") })
        assertEquals(2, sql.count { it.contains("deletedAt = NULL") })
        assertEquals(2, sql.count { it.contains("SET \"order\"") })
        assertTrue(sql.last().contains("refId"))
    }

    @Test
    fun `every failure is the one the screen answers to`() {
        rows.failWith = { RuntimeException("gone") }
        assertThrows(StoreUnavailable::class.java) { store.setLastOpened("x") }
        assertThrows(StoreUnavailable::class.java) { store.load() }
    }
}
