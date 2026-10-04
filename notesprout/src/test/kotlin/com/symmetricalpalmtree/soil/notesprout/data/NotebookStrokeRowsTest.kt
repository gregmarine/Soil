package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotebookStrokeRowsTest {

    private val columns = listOf("id", "order", "color", "strokeWidth", "style", "blob")
    private val blob = StrokeCodec.encode(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(0.5f, 0.6f), floatArrayOf(0f, 0f))

    private fun row(vararg cells: Cell) = Row(columns, cells.toList())

    @Test
    fun `a stroke row reads back`() {
        val (order, stroke) = requireNotNull(
            NotebookStrokeRows.decode(row(Cell.Text("s1"), Cell.Integer(4), Cell.Text("#808080"), Cell.Real(3.0), Cell.Text("PEN"), Cell.Blob(blob))),
        )
        assertEquals(4L, order)
        assertEquals("s1", stroke.id)
        assertEquals(0xFF808080.toInt(), stroke.color)
        assertEquals(3f, stroke.width)
        assertEquals(StrokeStyle.PEN, stroke.style)
        assertEquals(2, stroke.points.size)
        assertEquals(0.6f, stroke.points[1].pressure)
    }

    @Test
    fun `an unknown style, a null colour and a null width read as defaults`() {
        val (_, stroke) = requireNotNull(
            NotebookStrokeRows.decode(row(Cell.Text("s1"), Cell.Integer(0), Cell.Null, Cell.Null, Cell.Text("FUTURE"), Cell.Blob(blob))),
        )
        assertEquals(0xFF000000.toInt(), stroke.color)
        assertEquals(StrokeStyle.PEN, stroke.style)
    }

    @Test
    fun `a bad row is a dropped stroke`() {
        assertNull(NotebookStrokeRows.decode(row(Cell.Text("s1"), Cell.Integer(0), Cell.Null, Cell.Null, Cell.Null, Cell.Null)))
        assertNull(NotebookStrokeRows.decode(row(Cell.Text("s1"), Cell.Integer(0), Cell.Null, Cell.Null, Cell.Null, Cell.Blob(byteArrayOf(9, 9)))))
        assertNull(NotebookStrokeRows.decode(row(Cell.Null, Cell.Integer(0), Cell.Null, Cell.Null, Cell.Null, Cell.Blob(blob))))
    }
}
