package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateToken
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copy page's envelope (one page, a Day's two), and where a paste lands. */
class CalendarClipTest {

    private fun stroke(id: String, vararg xy: Float): Stroke =
        Stroke(id = id, points = xy.toList().chunked(2).map { (x, y) -> StrokePoint(x, y) })

    private fun ids(): () -> String { var n = 0; return { "id-${n++}" } }

    private val grid = byteArrayOf(10, 20, 30)
    private val otherGrid = byteArrayOf(11, 21, 31)

    @Test
    fun `one page is a template row, a page row at the page's size and its ink in writing order`() {
        val ink = listOf(4L to stroke("a", 1f, 2f, 3f, 4f), 9L to stroke("b", 5f, 6f))
        val env = CalendarClip.pageEnvelope(listOf(CalendarClip.PageCapture(1404f, 1872f, grid, ink)), 77L, ids())!!
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
        assertEquals(1404f, page.width)
        assertEquals(1872f, page.height)
        val strokes = env.rows.drop(2)
        assertEquals(listOf(page.id, page.id), strokes.map { it.parentId })
        assertEquals(listOf(4, 9), strokes.map { it.order })
        assertEquals(listOf("a", "b"), InkClip.strokesOf(env).map { it.id })
        assertEquals(listOf(1f, 3f), InkClip.strokesOf(env)[0].points.map { it.x })
        // Round-trips through the wire.
        assertEquals(env, ClipEnvelope.decode(ClipEnvelope.encode(env)))
    }

    @Test
    fun `a Day is two pages, AM then PM, each on its own grid, and two pages on one grid share a template row`() {
        val am = CalendarClip.PageCapture(1404f, 1872f, grid, listOf(0L to stroke("am", 1f, 1f)))
        val pm = CalendarClip.PageCapture(1404f, 1872f, otherGrid, listOf(0L to stroke("pm", 2f, 2f)))
        val env = CalendarClip.pageEnvelope(listOf(am, pm), 1L, ids())!!
        assertEquals(listOf("template", "template", "page", "page", "stroke", "stroke"), env.rows.map { it.type })
        val pages = env.rows.filter { it.type == "page" }
        assertEquals(listOf(0, 1), pages.map { it.order })
        assertEquals(listOf(env.rows[0].id, env.rows[1].id), pages.map { it.refId })
        val strokes = env.rows.filter { it.type == "stroke" }
        assertEquals(listOf(pages[0].id, pages[1].id), strokes.map { it.parentId })
        assertEquals(listOf("am", "pm"), strokes.map { it.id })

        val shared = CalendarClip.pageEnvelope(listOf(am, CalendarClip.PageCapture(1404f, 1872f, grid, emptyList())), 1L, ids())!!
        assertEquals(1, shared.rows.count { it.type == "template" })
        assertEquals(listOf(shared.rows[0].id, shared.rows[0].id), shared.rows.filter { it.type == "page" }.map { it.refId })
    }

    @Test
    fun `a page with no size or no grid is left out, an empty stroke is left out, nothing usable is null`() {
        assertNull(CalendarClip.pageEnvelope(emptyList(), 1L, ids()))
        assertNull(CalendarClip.pageEnvelope(listOf(CalendarClip.PageCapture(0f, 0f, grid, emptyList())), 1L, ids()))
        assertNull(CalendarClip.pageEnvelope(listOf(CalendarClip.PageCapture(10f, 10f, byteArrayOf(), emptyList())), 1L, ids()))
        val env = CalendarClip.pageEnvelope(
            listOf(CalendarClip.PageCapture(0f, 0f, grid, emptyList()), CalendarClip.PageCapture(10f, 10f, grid, listOf(0L to stroke("empty")))),
            1L, ids(),
        )
        assertNotNull(env)
        assertEquals(listOf("template", "page"), env!!.rows.map { it.type })
        assertEquals(0, env.rows[1].order)
    }

    @Test
    fun `an empty page still travels, so a blank Day half pastes as a blank papered page`() {
        val env = CalendarClip.pageEnvelope(listOf(CalendarClip.PageCapture(100f, 200f, grid, emptyList())), 1L, ids())!!
        assertEquals(listOf("template", "page"), env.rows.map { it.type })
        assertNotNull(ClipEnvelope.decode(ClipEnvelope.encode(env)))
    }

    @Test
    fun `a paste lands centred on the page under fresh ids, the shape kept`() {
        val placed = CalendarClip.placeCentred(listOf(stroke("a", 0f, 0f, 10f, 10f), stroke("b", 10f, 0f, 20f, 40f)), 100f, 200f, ids())
        assertEquals(listOf("id-0", "id-1"), placed.map { it.id })
        // The box 0..20 × 0..40 lands at 40..60 × 80..120.
        assertEquals(40f, placed[0].points[0].x)
        assertEquals(80f, placed[0].points[0].y)
        assertEquals(60f, placed[1].points[1].x)
        assertEquals(120f, placed[1].points[1].y)
    }

    @Test
    fun `wider than the page lands from the near edge, an unknown page size does not clamp, nothing is nothing`() {
        val wide = CalendarClip.placeCentred(listOf(stroke("a", 50f, 50f, 250f, 60f)), 100f, 200f, ids())
        assertEquals(0f, wide[0].points[0].x)
        assertEquals(95f, wide[0].points[0].y)
        val unknown = CalendarClip.placeCentred(listOf(stroke("a", 0f, 0f, 10f, 10f)), 0f, 0f, ids())
        assertEquals(-5f, unknown[0].points[0].x)
        assertTrue(CalendarClip.placeCentred(emptyList(), 100f, 100f, ids()).isEmpty())
        assertTrue(CalendarClip.placeCentred(listOf(stroke("e")), 100f, 100f, ids()).isEmpty())
    }
}
