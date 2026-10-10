package com.symmetricalpalmtree.soil.seamkit.clip

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateToken
import org.junit.Assert.assertArrayEquals
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

    // ── Copy page: a page payload ──────

    private fun ids(): () -> String { var n = 0; return { "id-${n++}" } }
    private val grid = byteArrayOf(10, 20, 30)
    private val otherGrid = byteArrayOf(11, 21, 31)

    @Test
    fun `one page is a template row, a page row at the page's size and its ink in writing order`() {
        val ink = listOf(4L to stroke("a", 1f, 2f), 9L to stroke("b", 5f, 6f))
        val env = InkClip.pageEnvelopeOf(listOf(InkClip.PageInk(1404f, 1872f, grid, ink)), 77L, ids())!!
        assertEquals(ClipEnvelope.KIND_PAGE, env.kind)
        assertEquals("", env.sourceNotebookId)
        assertEquals(77L, env.copiedAt)
        assertEquals(listOf("template", "page", "stroke", "stroke"), env.rows.map { it.type })
        val template = env.rows[0]
        assertEquals(TemplateToken.ofImage(grid, TemplateFit.FIT), template.text)
        assertTrue(TemplateToken.isImage(template.text!!))
        assertEquals(1404f, template.width)
        assertEquals(1872f, template.height)
        assertArrayEquals(grid, template.blobBytes())
        val page = env.rows[1]
        assertEquals(template.id, page.refId)
        assertEquals(0, page.order)
        assertEquals(1404f to 1872f, InkClip.pageSizeOf(env))
        val strokes = env.rows.drop(2)
        assertEquals(listOf(page.id, page.id), strokes.map { it.parentId })
        assertEquals(listOf(4, 9), strokes.map { it.order })
        assertEquals(listOf("a", "b"), InkClip.strokesOf(env).map { it.id })
        assertEquals(listOf(1f, 11f), InkClip.strokesOf(env)[0].points.map { it.x })
        assertEquals(env, ClipEnvelope.decode(ClipEnvelope.encode(env)))
    }

    @Test
    fun `two pages travel in order, each on its own paper, and two on one paper share a template row`() {
        val am = InkClip.PageInk(1404f, 1872f, grid, listOf(0L to stroke("am", 1f, 1f)))
        val pm = InkClip.PageInk(1404f, 1872f, otherGrid, listOf(0L to stroke("pm", 2f, 2f)))
        val env = InkClip.pageEnvelopeOf(listOf(am, pm), 1L, ids())!!
        assertEquals(listOf("template", "template", "page", "page", "stroke", "stroke"), env.rows.map { it.type })
        val pages = env.rows.filter { it.type == "page" }
        assertEquals(listOf(0, 1), pages.map { it.order })
        assertEquals(listOf(env.rows[0].id, env.rows[1].id), pages.map { it.refId })
        assertEquals(listOf(pages[0].id, pages[1].id), env.rows.filter { it.type == "stroke" }.map { it.parentId })

        val shared = InkClip.pageEnvelopeOf(listOf(am, InkClip.PageInk(1404f, 1872f, grid, emptyList())), 1L, ids())!!
        assertEquals(1, shared.rows.count { it.type == "template" })
        assertEquals(listOf(shared.rows[0].id, shared.rows[0].id), shared.rows.filter { it.type == "page" }.map { it.refId })
    }

    @Test
    fun `a page with no paper pastes blank, a page with no size is left out, an empty page still travels, nothing is null`() {
        val blank = InkClip.pageEnvelopeOf(listOf(InkClip.PageInk(100f, 200f, null, listOf(0L to stroke("a", 1f, 1f)))), 1L, ids())!!
        assertEquals(listOf("page", "stroke"), blank.rows.map { it.type })
        assertEquals("", blank.rows[0].refId)
        assertNull(InkClip.pageEnvelopeOf(emptyList(), 1L, ids()))
        assertNull(InkClip.pageEnvelopeOf(listOf(InkClip.PageInk(0f, 0f, grid, emptyList())), 1L, ids()))
        val env = InkClip.pageEnvelopeOf(listOf(InkClip.PageInk(0f, 0f, grid, emptyList()), InkClip.PageInk(10f, 10f, byteArrayOf(), emptyList())), 1L, ids())!!
        assertEquals(listOf("page"), env.rows.map { it.type })
        assertEquals(0, env.rows[0].order)
        assertNotNull(ClipEnvelope.decode(ClipEnvelope.encode(env)))
        assertNull(InkClip.pageSizeOf(InkClip.envelopeOf(listOf(stroke("a", 1f, 1f)), 1L)!!))
    }

    @Test
    fun `two pages' ink comes page by page, each in its own writing order, never interleaved`() {
        val am = InkClip.PageInk(1404f, 1872f, grid, listOf(9L to stroke("am-late", 1f, 1f), 5L to stroke("am-early", 1f, 1f)))
        val pm = InkClip.PageInk(1404f, 1872f, otherGrid, listOf(0L to stroke("pm-first", 2f, 2f), 1L to stroke("pm-second", 2f, 2f)))
        val env = InkClip.pageEnvelopeOf(listOf(am, pm), 1L, ids())!!
        assertEquals(listOf("am-early", "am-late", "pm-first", "pm-second"), InkClip.strokesOf(env).map { it.id })
        // The first page alone, as the pad and the calendar take it, by its page row.
        val first = env.rows.first { it.type == "page" }.id
        val onFirst = env.rows.filter { it.parentId == first }.mapTo(HashSet()) { it.id }
        assertEquals(listOf("am-early", "am-late"), InkClip.strokesOf(env).filter { it.id in onFirst }.map { it.id })
        assertEquals(1404f to 1872f, InkClip.pageSizeOf(env))
    }
}
