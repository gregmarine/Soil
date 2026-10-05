package com.symmetricalpalmtree.soil.seamkit.clip

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkClipTest {

    private fun stroke(id: String, x: Float, y: Float) = Stroke(
        id = id,
        points = listOf(StrokePoint(x, y, 0.5f, 0.1f, 0L), StrokePoint(x + 10f, y + 20f, 1f, 0f, 0L)),
        color = 0xFF000000.toInt(), width = 3f, style = StrokeStyle.PEN,
    )

    @Test
    fun `ink copied from the pad is an objects payload of stroke rows under a page that is not there`() {
        val env = InkClip.envelopeOf(listOf(stroke("a", 10f, 10f), stroke("b", 50f, 10f)), now = 99L)!!
        assertEquals(ClipEnvelope.KIND_OBJECTS, env.kind)
        assertEquals("", env.sourceNotebookId)
        assertEquals(99L, env.copiedAt)
        assertEquals(listOf("a", "b"), env.rows.map { it.id })
        assertTrue(env.rows.all { it.type == "stroke" })
        assertEquals(1, env.rows.map { it.parentId }.toSet().size)
        assertTrue(env.rows.none { row -> env.rows.any { it.id == row.parentId } })
    }

    @Test
    fun `the ink reads back from the stored bytes, point for point and in writing order`() {
        val strokes = listOf(stroke("a", 10f, 10f), stroke("b", 50f, 10f))
        val bytes = ClipEnvelope.encode(InkClip.envelopeOf(strokes, 1L)!!)
        val back = InkClip.strokesOf(ClipEnvelope.decode(bytes)!!)
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertEquals(strokes[1].points.map { it.x to it.y }, back[1].points.map { it.x to it.y })
        assertEquals(3f, back[0].width, 0f)
        assertEquals(StrokeStyle.PEN, back[0].style)
    }

    @Test
    fun `nothing to copy is no clipboard, and a stroke with no points is not ink`() {
        assertNull(InkClip.envelopeOf(emptyList(), 1L))
        assertNull(InkClip.envelopeOf(listOf(Stroke(id = "x", points = emptyList(), color = 0, width = 1f, style = StrokeStyle.PEN)), 1L))
    }

    @Test
    fun `a sticky note's own strokes are not handwriting on the page, and a row that does not read is dropped`() {
        val good = InkClip.envelopeOf(listOf(stroke("a", 1f, 1f)), 1L)!!.rows.single()
        val env = ClipEnvelope(
            ClipEnvelope.VERSION, ClipEnvelope.KIND_OBJECTS, "nb", 1L,
            listOf(
                good.copy(parentId = "page"),
                ClipRow(id = "note", parentId = "page", type = "sticky_note"),
                good.copy(id = "in-note", parentId = "note"),
                good.copy(id = "broken", parentId = "page", blob = "not base64 at all!"),
                ClipRow(id = "h", parentId = "page", type = "heading", text = "Title"),
            ),
        )
        assertEquals(listOf("a"), InkClip.strokesOf(env).map { it.id })
        assertNotNull(ClipEnvelope.encode(env))
    }
}
