package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.store.Cell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `stroke` half every consumer composes, pinned where it lives — exact DDL and exact statement text. */
class InkSqlTest {

    private fun stroke(id: String = "s1") = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
        style = StrokeStyle.PEN,
    )

    @Test
    fun theTableIsOneShape() {
        assertEquals(
            """CREATE TABLE stroke (
                       id TEXT PRIMARY KEY,
                       pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                       "order" INTEGER NOT NULL,
                       color INTEGER NOT NULL,
                       width REAL NOT NULL,
                       style TEXT NOT NULL,
                       blob BLOB NOT NULL);""",
            InkSql.CREATE_STROKE_TABLE,
        )
        assertEquals(
            """CREATE INDEX stroke_page_order ON stroke(pageId, "order");""",
            InkSql.CREATE_STROKE_INDEX,
        )
        assertTrue(InkSql.CREATE_STROKE_TABLE.contains("ON DELETE CASCADE"))
    }

    @Test
    fun putStroke_isIdempotent_andCarriesFormatBGeometry() {
        val s = InkSql.putStroke("p1", 4L, stroke())
        assertEquals(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(Cell.Text("s1"), s.args[0])
        assertEquals(Cell.Text("p1"), s.args[1])
        assertEquals(Cell.Integer(4), s.args[2])
        assertEquals(Cell.Integer(Stroke.BLACK.toLong()), s.args[3])
        assertEquals(Cell.Real(3.0), s.args[4])
        assertEquals(Cell.Text("PEN"), s.args[5])
        assertArrayEquals(StrokeBlob.encode(stroke()), (s.args[6] as Cell.Blob).value)
    }

    @Test
    fun theDeletes() {
        val drop = InkSql.dropStroke("s1")
        assertEquals("DELETE FROM stroke WHERE id = ?", drop.sql)
        assertEquals(listOf<Cell>(Cell.Text("s1")), drop.args)

        val clear = InkSql.clearStrokes("p1")
        assertEquals("DELETE FROM stroke WHERE pageId = ?", clear.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), clear.args)
    }

    @Test
    fun theRead_keepsOrderQuoted() {
        val strokes = InkSql.selectStrokes("p1")
        assertEquals(
            "SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? ORDER BY \"order\"",
            strokes.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("p1")), strokes.args)
    }
}
