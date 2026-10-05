package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSqlTest {

    @Test
    fun `every write passes the seam's checker with a bind for every argument`() {
        val writes = listOf(
            DocumentSql.insertRoot("doc", 5L),
            DocumentSql.insertBody("body", "doc", 5L),
            DocumentSql.setBody("body", "# Words", 6L),
        )
        for (statement in writes) {
            SeamSql.checkExec(statement.sql)
            assertEquals(statement.sql, statement.args.size, SeamSql.bindCount(statement.sql))
        }
    }

    @Test
    fun `the read passes the seam's checker`() {
        val read = DocumentSql.selectBody("doc")
        SeamSql.checkQuery(read.sql)
        assertEquals(1, SeamSql.bindCount(read.sql))
        assertEquals(listOf<Cell>(Cell.Text("doc")), read.args)
    }

    @Test
    fun `the rows are made idempotently and the body is only updated`() {
        assertTrue(DocumentSql.insertRoot("doc", 1L).sql.startsWith("INSERT OR IGNORE"))
        assertTrue(DocumentSql.insertBody("b", "doc", 1L).sql.startsWith("INSERT OR IGNORE"))
        val set = DocumentSql.setBody("b", "text", 9L)
        assertTrue(set.sql.startsWith("UPDATE"))
        assertEquals(listOf(Cell.Text("text"), Cell.Integer(9L), Cell.Text("b")), set.args)
    }
}
