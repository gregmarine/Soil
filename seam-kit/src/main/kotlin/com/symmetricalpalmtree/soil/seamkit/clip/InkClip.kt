package com.symmetricalpalmtree.soil.seamkit.clip

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.ink.StrokeRows
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateToken

/**
 * **Ink on the clipboard**, for the two things that are not a notebook: the Scratch Pad, which
 * copies its ink there, and a document, which pastes ink as recognised words.
 *
 * There is one clipboard for ink and it is the notebook kind's ([SLOT]): the pad and the calendar
 * write what a lasso Copy in a notebook would have written, an objects payload of stroke rows
 * ([envelopeOf]), or what its Copy page would have, a page payload ([pageEnvelopeOf]), so a
 * notebook pastes their ink with the Paste it already has, and a document reads ink whichever
 * surface copied it. Pure.
 */
object InkClip {

    /** The clipboard slot ink lives in: the notebook kind's. */
    const val SLOT = "notebook"

    /**
     * The slot beside it that keeps the words ink was read as, so a second paste into a document
     * does not read it again. It belongs to the ink whose `copiedAt` its own header carries; the
     * words of any other ink are stale, and are not used.
     */
    const val WORDS_SLOT = "ink_words"
    const val WORDS_KIND = "text"

    private const val TYPE_STROKE = "stroke"
    private const val TYPE_STICKY = "sticky_note"

    /** The parent every row of a pad copy names: a page that is not in the payload, which is how
     *  a paste knows these rows are the top of what was copied. */
    private const val PAD_PAGE = "scratch-pad"

    /** [strokes] as a lasso Copy would have put them on the clipboard, or null for none. No
     *  notebook is named as the source: the pad is no notebook, and nothing in ink re-points. */
    fun envelopeOf(strokes: List<Stroke>, now: Long): ClipEnvelope? {
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) return null
        return ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_OBJECTS, sourceNotebookId = "", copiedAt = now,
            rows = ink.mapIndexed { i, s ->
                ClipRow(
                    id = s.id, parentId = PAD_PAGE, type = TYPE_STROKE, order = i,
                    color = InkColorCodec.encode(s.color), strokeWidth = s.width, style = s.style.name,
                    blob = ClipRow.encodeBlob(StrokeBlob.encode(s)),
                )
            },
        )
    }

    private const val TYPE_TEMPLATE = "template"
    private const val TYPE_PAGE = "page"

    /** One page as a Copy page captures it: its size, its paper as a picture's bytes (or null for
     *  blank), its ink `(order, stroke)` in writing order. */
    class PageInk(val width: Float, val height: Float, val paper: ByteArray?, val strokes: List<Pair<Long, Stroke>>)

    /**
     * [pages] as a notebook's own Copy page would have put them on the clipboard — a
     * [ClipEnvelope.KIND_PAGE] payload — so the notebook's page sheet pastes them before or after
     * the page showing, every page in order (the calendar's Copy page of a Day: AM then PM — Greg,
     * 2026-10-05). A page's paper travels as a `template` row carrying the picture under the
     * [TemplateToken.ofImage] token, so a notebook that already holds the same bytes reuses its
     * row rather than minting another; two pages on the same bytes share one row; a page with no
     * paper pastes blank. The page row is at the page's size, the stroke rows its ink. No notebook
     * is named as the source. Null when every page is unusable (no size); an empty page still
     * travels, so a blank half of a Day pastes as a blank papered page.
     */
    fun pageEnvelopeOf(pages: List<PageInk>, now: Long, newId: () -> String): ClipEnvelope? {
        val usable = pages.filter { it.width > 0f && it.height > 0f }
        if (usable.isEmpty()) return null
        val templates = LinkedHashMap<String, ClipRow>()
        val pageRows = ArrayList<ClipRow>(usable.size)
        val strokeRows = ArrayList<ClipRow>()
        for ((i, page) in usable.withIndex()) {
            val paper = page.paper?.takeIf { it.isNotEmpty() }
            val template = paper?.let { bytes ->
                templates.getOrPut(TemplateToken.ofImage(bytes, TemplateFit.FIT)) {
                    ClipRow(
                        id = newId(), parentId = "", type = TYPE_TEMPLATE, order = 0, text = TemplateToken.ofImage(bytes, TemplateFit.FIT),
                        width = page.width, height = page.height, blob = ClipRow.encodeBlob(bytes),
                    )
                }
            }
            val pageId = newId()
            pageRows += ClipRow(id = pageId, parentId = "", type = TYPE_PAGE, order = i, refId = template?.id ?: "", width = page.width, height = page.height)
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

    /** The size the payload's first page row names, or null when it names none. */
    fun pageSizeOf(env: ClipEnvelope): Pair<Float, Float>? {
        val page = env.rows.firstOrNull { it.type == TYPE_PAGE } ?: return null
        val w = page.width ?: return null
        val h = page.height ?: return null
        return w to h
    }

    /**
     * The handwriting in a payload, in writing order: every stroke row but a sticky note's own
     * (those are in the note's space, not the page's). A row that does not read is dropped. A
     * page payload, an objects payload and a pad copy all answer here. A page payload's ink comes
     * page by page, in the pages' order, each page's in its own writing order: `order` counts
     * within a page, so two pages' strokes are never interleaved.
     */
    fun strokesOf(env: ClipEnvelope): List<Stroke> {
        val stickies = env.rows.filter { it.type == TYPE_STICKY }.mapTo(HashSet()) { it.id }
        val pageIndex = HashMap<String, Int>()
        env.rows.filter { it.type == TYPE_PAGE }.sortedBy { it.order }.forEachIndexed { i, row -> pageIndex.putIfAbsent(row.id, i) }
        return env.rows.filter { it.type == TYPE_STROKE && it.parentId !in stickies }
            .sortedWith(compareBy<ClipRow>({ pageIndex[it.parentId] ?: -1 }, { it.order }))
            .mapNotNull(::strokeOf)
    }

    private fun strokeOf(row: ClipRow): Stroke? = try {
        val points = StrokeCodec.decode(row.blobBytes() ?: return null)
        if (points.size == 0) null else {
            val list = ArrayList<StrokePoint>(points.size)
            for (i in 0 until points.size) list += StrokePoint(points.x[i], points.y[i], points.pressure?.get(i) ?: 1f, points.tilt?.get(i) ?: 0f, 0L)
            Stroke(id = row.id, points = list, color = InkColorCodec.decode(row.color), width = row.strokeWidth ?: Stroke.DEFAULT_WIDTH, style = StrokeRows.styleOf(row.style.orEmpty()))
        }
    } catch (_: Exception) {
        null
    }
}
