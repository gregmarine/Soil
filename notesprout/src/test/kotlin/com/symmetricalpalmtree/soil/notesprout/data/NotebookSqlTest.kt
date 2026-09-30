package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookSqlTest {

    private val stroke = Stroke(
        id = "s1",
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0f, 0L), StrokePoint(3f, 4f, 0.6f, 0f, 0L)),
        color = 0xFF808080.toInt(), width = 3f, style = StrokeStyle.PEN,
    )

    private val writes = listOf(
        NotebookSql.insertRoot("nb", "Journal", 10L),
        NotebookSql.setLastOpened("nb", "p1", 10L),
        NotebookSql.setTitle("nb", "Journal", 10L),
        NotebookSql.insertPage("p1", "nb", 0, 1404f, 1872f, "", 10L),
        NotebookSql.setOrder("p1", 2, 10L),
        NotebookSql.softDelete("p1", 10L),
        NotebookSql.restore("p1"),
        NotebookSql.putStroke("p1", 7L, stroke, 10L),
        NotebookSql.dropStroke("s1", 10L),
    )

    private val reads = listOf(
        NotebookSql.selectRoot("nb"),
        NotebookSql.selectPages("nb"),
        NotebookSql.selectLiveDescendantIds("p1"),
        NotebookSql.selectStrokes("p1"),
    )

    @Test
    fun `every statement passes the seam's checker with its binds matched`() {
        for (s in writes) {
            SeamSql.checkExec(s.sql)
            assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size)
        }
        for (s in reads) {
            SeamSql.checkQuery(s.sql)
            assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size)
        }
    }

    @Test
    fun `order is always quoted`() {
        for (s in writes + reads) assertFalse(s.sql, Regex("[^\"]order[^\"]").containsMatchIn(s.sql.replace("\"order\"", "")))
    }

    @Test
    fun `a page row is never replaced`() {
        assertTrue(NotebookSql.insertPage("p", "nb", 0, 1f, 1f, "", 0L).sql.startsWith("INSERT OR IGNORE"))
        assertFalse(writes.any { it.sql.contains("OR REPLACE") })
    }

    @Test
    fun `a stroke is written as SN wrote it and keeps its createdAt on a re-put`() {
        val s = NotebookSql.putStroke("p1", 7L, stroke, 10L)
        assertEquals(Cell.Text("s1"), s.args[0])
        assertEquals(Cell.Text("p1"), s.args[1])
        assertEquals(Cell.Integer(7L), s.args[2])
        assertEquals(Cell.Text("#808080"), s.args[5])
        assertEquals(Cell.Real(3.0), s.args[6])
        assertEquals(Cell.Text("PEN"), s.args[7])
        assertTrue(s.args[8] is Cell.Blob)
        assertTrue(s.sql.contains("ON CONFLICT(id) DO UPDATE"))
        assertFalse(s.sql.substringAfter("DO UPDATE").contains("createdAt"))
        assertTrue(s.sql.contains("deletedAt = NULL"))
    }

    @Test
    fun `a dropped stroke is soft-deleted, never deleted`() {
        val s = NotebookSql.dropStroke("s1", 10L)
        assertTrue(s.sql.startsWith("UPDATE"))
        assertTrue(s.sql.contains("deletedAt = ?"))
        assertEquals(listOf<Cell>(Cell.Integer(10L), Cell.Text("s1")), s.args)
    }

    @Test
    fun `housekeeping never bumps updatedAt`() {
        assertFalse(NotebookSql.softDelete("x", 1L).sql.contains("updatedAt"))
        assertFalse(NotebookSql.restore("x").sql.contains("updatedAt"))
    }

    @Test
    fun `the reads name the columns the decoders expect`() {
        val strokes: Statement = NotebookSql.selectStrokes("p")
        for (c in listOf("id", "\"order\"", "color", "strokeWidth", "style", "blob")) assertTrue(strokes.sql.contains(c))
        val pages = NotebookSql.selectPages("nb")
        for (c in listOf("id", "\"order\"", "width", "height", "refId")) assertTrue(pages.sql.contains(c))
        assertTrue(pages.sql.contains("deletedAt IS NULL"))
        assertTrue(strokes.sql.contains("deletedAt IS NULL"))
    }
}
