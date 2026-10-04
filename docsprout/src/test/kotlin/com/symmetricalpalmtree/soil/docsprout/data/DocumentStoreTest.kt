package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentStoreTest {

    /** The one body row of the table, as far as the store's statements go. There is no SQLite on
     *  the JVM: real SQL is proved on the Nomad. */
    private class FakeRows(var bodyId: String? = null, var body: String? = null) : RowStore {
        val execs = ArrayList<List<Statement>>()
        var failWith: (() -> Throwable)? = null

        override fun exec(statements: List<Statement>): LongArray {
            failWith?.let { throw it() }
            execs += statements
            return LongArray(statements.size) { i ->
                val s = statements[i]
                when {
                    s.sql.contains("'body'") && s.sql.startsWith("INSERT") -> {
                        if (bodyId == null) { bodyId = (s.args[0] as Cell.Text).value; body = ""; 1L } else 0L
                    }
                    s.sql.startsWith("UPDATE") -> {
                        if ((s.args[2] as Cell.Text).value == bodyId) { body = (s.args[0] as Cell.Text).value; 1L } else 0L
                    }
                    else -> 1L
                }
            }
        }

        override fun query(statement: Statement): StoreRows {
            failWith?.let { throw it() }
            val id = bodyId
            return StoreRows(listOf("id", "text"), if (id == null) emptyList() else listOf(listOf(Cell.Text(id), body?.let { Cell.Text(it) } ?: Cell.Null)))
        }
    }

    @Test
    fun `a file nobody has written is given its root and an empty body, in one transaction`() {
        val rows = FakeRows()
        val store = DocumentStore(rows, "doc") { "body-1" }
        assertEquals("", store.load(now = 7L))
        assertEquals(1, rows.execs.size)
        assertEquals(2, rows.execs[0].size)
        assertEquals("body-1", rows.bodyId)
    }

    @Test
    fun `a written file reads back and is not written by the read`() {
        val rows = FakeRows(bodyId = "b", body = "# Title\n\nWords.")
        assertEquals("# Title\n\nWords.", DocumentStore(rows, "doc").load())
        assertTrue(rows.execs.isEmpty())
    }

    @Test
    fun `a save replaces the whole body`() {
        val rows = FakeRows(bodyId = "b", body = "old")
        val store = DocumentStore(rows, "doc")
        store.load()
        store.save("new", now = 9L)
        assertEquals("new", rows.body)
    }

    @Test
    fun `a blank save is saved as blank`() {
        val rows = FakeRows(bodyId = "b", body = "old")
        val store = DocumentStore(rows, "doc")
        store.load()
        store.save("")
        assertEquals("", rows.body)
        assertEquals("b", rows.bodyId)
    }

    @Test
    fun `a save before a load is refused`() {
        assertThrows(StoreUnavailable::class.java) { DocumentStore(FakeRows(bodyId = "b", body = ""), "doc").save("x") }
    }

    @Test
    fun `a save that lands on no row is a failure, not a success`() {
        val rows = FakeRows(bodyId = "b", body = "old")
        val store = DocumentStore(rows, "doc")
        store.load()
        rows.bodyId = "someone-else"
        assertThrows(StoreUnavailable::class.java) { store.save("new") }
    }

    @Test
    fun `any failure of the store is the store being unavailable`() {
        val rows = FakeRows(bodyId = "b", body = "old")
        val store = DocumentStore(rows, "doc")
        store.load()
        rows.failWith = { IllegalStateException("gone") }
        assertThrows(StoreUnavailable::class.java) { store.save("new") }
        assertThrows(StoreUnavailable::class.java) { store.load() }
    }

    @Test
    fun `a document fits until its UTF-8 passes the limit`() {
        assertTrue(DocumentLimits.fits(""))
        assertTrue(DocumentLimits.fits("a".repeat(DocumentLimits.MAX_BODY_BYTES)))
        assertFalse(DocumentLimits.fits("a".repeat(DocumentLimits.MAX_BODY_BYTES + 1)))
        // Two bytes each: half as many fit.
        assertTrue(DocumentLimits.fits("é".repeat(DocumentLimits.MAX_BODY_BYTES / 2)))
        assertFalse(DocumentLimits.fits("é".repeat(DocumentLimits.MAX_BODY_BYTES / 2 + 1)))
    }
}
