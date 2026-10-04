package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Cell
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
        assertThrows(NotebookStore.NoPages::class.java) { store.load() }
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
        assertEquals(3, sql.size)
        assertEquals(2, sql.count { it.contains("deletedAt = ?") })
        assertTrue(sql.last().startsWith("DELETE FROM soil_link WHERE pageId"))
        assertTrue(rows.statements.none { it.sql.contains("deletedAt = ?") && (it.args[1] as com.symmetricalpalmtree.soil.paper.store.Cell.Text).value == "a" })
        // An empty page's erase writes nothing.
        rows.pages = listOf(page("b"))
        assertTrue(store.erasePage("b").isEmpty())
        assertEquals(3, rows.sql().size)
    }

    @Test
    fun `reconcile restores in place, deletes what is not wanted, and orders the rest`() {
        val a = PageRef("a", 1f, 1f, "")
        val b = PageRef("b", 1f, 1f, "")
        rows.links = mapOf("b" to listOf("l1" to "L1|1|0||p9"))
        store.reconcile(alive = listOf(a), target = listOf(b, a), restoreIds = listOf("s1"), deleteIds = emptyList(), currentId = "b")
        val sql = rows.execs[0].map { it.sql }
        assertEquals(2, rows.execs.size)
        assertTrue(sql.any { it.startsWith("INSERT OR IGNORE") })
        assertEquals(2, sql.count { it.contains("deletedAt = NULL") })
        assertEquals(2, sql.count { it.contains("SET \"order\"") })
        assertTrue(sql.last().contains("refId"))
        // The page brought back brings its links back: their mirror rows are written again.
        val mirror = rows.execs[1]
        assertTrue(mirror[0].sql.startsWith("DELETE FROM soil_link WHERE pageId"))
        assertTrue(mirror[1].sql.startsWith("INSERT INTO soil_link"))
        assertEquals(Cell.Text("nb"), mirror[1].args[2])
        assertEquals(Cell.Text("p9"), mirror[1].args[3])
    }

    // ── Links ──────

    private val wrap = com.symmetricalpalmtree.soil.notesprout.objects.PageLink(
        id = "l1", payload = "L1|1|2|other|p2", chrome = 1, x = 0f, y = 0f, width = 10f, height = 10f, order = 0,
        strokes = emptyList(),
        headings = listOf(com.symmetricalpalmtree.soil.notesprout.objects.Heading("h1", "# A", 1, 0f, 0f, 5f, 5f, 0)),
        stickies = listOf(com.symmetricalpalmtree.soil.notesprout.objects.PageSticky("st1", 1f, 1f, 2f, 2f, 10, 10, 0)),
    )

    @Test
    fun `a wrap is the link row, every child re-parented and the mirror row, in one transaction`() {
        val placed = store.createLink("a", wrap)
        assertEquals(3, placed.order)
        assertEquals(1, rows.execs.size)
        val batch = rows.execs[0]
        assertTrue(batch[0].sql.contains("'link'"))
        assertEquals(2, batch.count { it.sql.contains("SET parentId = ?") })
        assertTrue(batch.filter { it.sql.contains("SET parentId = ?") }.all { it.args[0] == Cell.Text("l1") })
        val mirror = batch.last()
        assertTrue(mirror.sql.startsWith("INSERT INTO soil_link"))
        assertEquals(listOf<Cell>(Cell.Text("l1"), Cell.Text("a"), Cell.Text("other"), Cell.Text("p2")), mirror.args)
    }

    @Test
    fun `an unlink gives the children back to the page and drops the mirror row`() {
        store.unlink("a", wrap)
        val batch = rows.execs.single()
        assertTrue(batch.filter { it.sql.contains("SET parentId = ?") }.all { it.args[0] == Cell.Text("a") })
        assertTrue(batch.any { it.sql.contains("deletedAt = ?") && it.args[1] == Cell.Text("l1") })
        assertTrue(batch.last().sql.startsWith("DELETE FROM soil_link WHERE id"))
    }

    @Test
    fun `a link to a page of this notebook mirrors this notebook's own id, an unreadable one has no mirror row`() {
        store.setLinkPayload("a", wrap.copy(payload = "L1|0|0||p7"))
        val mirror = rows.execs.single().last()
        assertEquals(Cell.Text("nb"), mirror.args[2])
        assertEquals(Cell.Text("p7"), mirror.args[3])
        rows.execs.clear()
        store.setLinkPayload("a", wrap.copy(payload = "garbage"))
        assertTrue(rows.execs.single().last().sql.startsWith("DELETE FROM soil_link WHERE id"))
    }

    @Test
    fun `deleting a link takes everything under it and its mirror row`() {
        rows.pages = listOf(page("l1", "h1", "st1", "s9"))
        store.deleteObjects(listOf("l1"), emptyList(), listOf("l1"))
        val batch = rows.execs.single()
        assertEquals(4, batch.count { it.sql.contains("deletedAt = ?") })
        assertTrue(batch.last().sql.startsWith("DELETE FROM soil_link WHERE id"))
    }

    @Test
    fun `a page read carries its links with what they wrap`() {
        rows.links = mapOf("a" to listOf("l1" to "L1|1|1|other|", "l2" to "nonsense"))
        val content = store.readPage(PageRef("a", 1f, 1f, ""))
        assertEquals(listOf("l1", "l2"), content.links.map { it.id })
        assertEquals(1, content.links[0].chrome)
        assertEquals(0, content.links[1].chrome)
    }

    @Test
    fun `every failure is the one the screen answers to`() {
        rows.failWith = { RuntimeException("gone") }
        assertThrows(StoreUnavailable::class.java) { store.setLastOpened("x") }
        assertThrows(StoreUnavailable::class.java) { store.load() }
    }
}
