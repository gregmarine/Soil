package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateToken
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.ClipRow

/**
 * **Ink across, the calendar's half** (Greg, 2026-10-05: copy and paste, never Send). Pure.
 *
 * **Copy page** writes what a notebook's own Copy page writes — a [ClipEnvelope.KIND_PAGE]
 * payload on the notebook slot — so the notebook's page sheet pastes it before or after the page
 * showing with the Paste it already has. The calendar's grid travels as the page's **paper**: a
 * `template` row carrying the grid rendered alone (no ring, no marks — SN HV5's rule) as a
 * picture, under the [TemplateToken.ofImage] token, so a notebook that already holds the same
 * grid reuses its row by bytes rather than minting another. The page row is at the page's size,
 * the stroke rows are the page's ink in writing order, and `sourceNotebookId` is empty: the
 * calendar is no notebook, and nothing in ink re-points.
 *
 * **A Day copies both halves, AM then PM, as two pages** in one envelope (Greg): the notebook's
 * paste takes every page row in order. Two pages that share a grid share one template row.
 *
 * **Paste** lands a lasso's strokes (a notebook's or the pad's objects payload) **centred on the
 * showing page**, each under a fresh id, pulled onto the page when it would hang off an edge.
 */
object CalendarClip {

    /** One page as Copy page captures it: its size, its grid as WEBP bytes, its ink in order. */
    class PageCapture(val width: Float, val height: Float, val grid: ByteArray, val strokes: List<Pair<Long, Stroke>>)

    private const val TYPE_TEMPLATE = "template"
    private const val TYPE_PAGE = "page"
    private const val TYPE_STROKE = "stroke"

    /**
     * The envelope for [pages], in order. Null when every page is unusable (no size). A page's
     * grid is its template row; the same bytes twice is one row, named by both pages.
     */
    fun pageEnvelope(pages: List<PageCapture>, now: Long, newId: () -> String): ClipEnvelope? {
        val usable = pages.filter { it.width > 0f && it.height > 0f && it.grid.isNotEmpty() }
        if (usable.isEmpty()) return null
        val templates = LinkedHashMap<String, ClipRow>()
        val pageRows = ArrayList<ClipRow>(usable.size)
        val strokeRows = ArrayList<ClipRow>()
        for ((i, page) in usable.withIndex()) {
            val token = TemplateToken.ofImage(page.grid, TemplateFit.FIT)
            val template = templates.getOrPut(token) {
                ClipRow(
                    id = newId(), parentId = "", type = TYPE_TEMPLATE, order = 0, text = token,
                    width = page.width, height = page.height, blob = ClipRow.encodeBlob(page.grid),
                )
            }
            val pageId = newId()
            pageRows += ClipRow(id = pageId, parentId = "", type = TYPE_PAGE, order = i, refId = template.id, width = page.width, height = page.height)
            for ((order, s) in page.strokes) {
                if (s.points.isEmpty()) continue
                strokeRows += ClipRow(
                    id = s.id, parentId = pageId, type = TYPE_STROKE, order = order.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    color = InkColorCodec.encode(s.color), strokeWidth = s.width, style = s.style.name,
                    blob = ClipRow.encodeBlob(StrokeBlob.encode(s)),
                )
            }
        }
        // Every template row first, then the pages, then their ink: the order a paste inserts in.
        return ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_PAGE, sourceNotebookId = "", copiedAt = now,
            rows = templates.values.toList() + pageRows + strokeRows,
        )
    }

    /**
     * [strokes] as they land on a page of [pageWidth] × [pageHeight]: their box centred on the
     * page, pulled back onto it from the far edge first and then off the near one, under fresh
     * ids from [newId]. A box larger than the page on an axis lands from that edge. Empty for
     * nothing to place.
     */
    fun placeCentred(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float, newId: () -> String): List<Stroke> {
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) return emptyList()
        var box: Bounds = ink.first().bounds
        for (i in 1 until ink.size) box = box.union(ink[i].bounds)
        val dx = axis(box.left, box.width, (pageWidth - box.width) / 2f, pageWidth)
        val dy = axis(box.top, box.height, (pageHeight - box.height) / 2f, pageHeight)
        return ink.map { it.translated(dx, dy, newId()) }
    }

    /** One axis: the shift from [from] to [target], clamped onto a page of [page]; unclamped when the page size is unknown. */
    private fun axis(from: Float, size: Float, target: Float, page: Float): Float {
        if (!from.isFinite() || !size.isFinite() || !target.isFinite()) return 0f
        var shift = target - from
        if (page <= 0f) return shift
        if (size > page) return -from
        val overshoot = (from + size + shift) - page
        if (overshoot > 0f) shift -= overshoot
        val undershoot = from + shift
        if (undershoot < 0f) shift -= undershoot
        return shift
    }
}
