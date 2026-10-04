package com.symmetricalpalmtree.soil.notesprout.objects

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectsTest {

    private val columns = listOf("id", "type", "order", "text", "refId", "x", "y", "width", "height", "flags")

    private fun row(type: String, text: String? = null, x: Double? = 10.0, y: Double? = 20.0, w: Double? = 100.0, h: Double? = 50.0, flags: Long? = null) =
        Row(columns, listOf(
            Cell.Text("id-1"), Cell.Text(type), Cell.Integer(3), text?.let { Cell.Text(it) } ?: Cell.Null, Cell.Null,
            x?.let { Cell.Real(it) } ?: Cell.Null, y?.let { Cell.Real(it) } ?: Cell.Null, w?.let { Cell.Real(it) } ?: Cell.Null, h?.let { Cell.Real(it) } ?: Cell.Null,
            flags?.let { Cell.Integer(it) } ?: Cell.Null,
        ))

    @Test
    fun `a heading row reads back, its level clamped, its text required`() {
        val h = requireNotNull(ObjectRows.toHeading(row("heading", "## Title", flags = 2)))
        assertEquals(2, h.level)
        assertEquals("## Title", h.text)
        assertEquals(3, h.order)
        assertEquals(Bounds(10f, 20f, 110f, 70f), h.bounds)
        assertEquals(6, ObjectRows.toHeading(row("heading", "# T", flags = 99))!!.level)
        assertNull(ObjectRows.toHeading(row("heading", null)))
    }

    @Test
    fun `a text row is dropped when blank or with bad geometry`() {
        assertNotNull(ObjectRows.toText(row("text", "words")))
        assertNull(ObjectRows.toText(row("text", "  ")))
        assertNull(ObjectRows.toText(row("text", "w", w = -1.0)))
    }

    @Test
    fun `a sticky row reads its content size`() {
        val st = requireNotNull(ObjectRows.toSticky(row("sticky_note", flags = StickyFlags.pack(1404, 1872))))
        assertEquals(1404, st.contentW)
        assertEquals(1872, st.contentH)
        assertTrue(st.strokes.isEmpty())
        assertNull(ObjectRows.toSticky(row("sticky_note", x = null)))
    }

    @Test
    fun `a drop lands at the centre when clear, else at the nearest clear spot`() {
        assertEquals(450f to 950f, FreePlacement.nearCentre(1000f, 2000f, 100f, 100f, emptyList(), 2f))
        val taken = listOf(Bounds(400f, 900f, 600f, 1100f))
        val (x, y) = FreePlacement.nearCentre(1000f, 2000f, 100f, 100f, taken, 2f)
        assertTrue(FreePlacement.clear(x, y, 100f, 100f, 16f, taken))
        assertTrue(kotlin.math.abs(x - 450f) + kotlin.math.abs(y - 950f) > 0f)
        // A box the page cannot hold lands at the centre regardless.
        assertEquals(0f to 0f, FreePlacement.nearCentre(50f, 50f, 100f, 100f, taken, 2f))
    }

    @Test
    fun `typed text keeps its interior and drops outer blank lines`() {
        assertEquals("a\nb\n\n\nc", TextLines.typed("\n\na  \nb\n\n\nc   \n\n"))
        assertEquals("", TextLines.typed(" \n \n"))
        assertEquals("a b\n\nc", TextLines.normalize("  a   b \n\n\n c\r\n"))
    }

    @Test
    fun `a selection is one mode`() {
        val one = { id: String -> id == "h" }
        assertEquals(SelectionMode.HEADING, SelectionModes.classify(0, listOf("h"), one, { false }, { false }))
        assertEquals(SelectionMode.STROKES, SelectionModes.classify(3, emptyList(), one, { false }, { false }))
        assertEquals(SelectionMode.MIXED, SelectionModes.classify(3, listOf("h"), one, { false }, { false }))
        assertEquals(SelectionMode.STICKY, SelectionModes.classify(0, listOf("n"), { false }, { false }, { false }, { it == "n" }))
        assertEquals(SelectionMode.MIXED_WITH_LINK, SelectionModes.classify(0, listOf("l", "h"), one, { it == "l" }, { false }))
    }

    @Test
    fun `the note's off-page bands never overlap`() {
        assertTrue(StickyPageRects.offPage(1000, 2000, 1000, 2000).isEmpty())
        val bands = StickyPageRects.offPage(800, 1500, 1000, 2000)
        assertEquals(listOf(StickyPageRects.Band(0, 1500, 1000, 2000), StickyPageRects.Band(800, 0, 1000, 1500)), bands)
        assertTrue(StickyPageRects.offPage(800, 1500, 0, 0).isEmpty())
    }
}
