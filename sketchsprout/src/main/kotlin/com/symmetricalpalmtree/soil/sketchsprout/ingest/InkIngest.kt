package com.symmetricalpalmtree.soil.sketchsprout.ingest

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip

/**
 * **Convert, the sketchbook's half** (design §4): the ink file a notebook hands Soil — the
 * notebook's own page envelope ([InkClip.pageEnvelopeOf]: a page row per page at its size, its
 * paper as a template row carrying the picture, its strokes) — read as the pages a new sketchbook
 * is made of. Pure: what crosses from the file to the rows is decided here and pinned on the JVM;
 * the baking of strokes into pixels is the service's.
 *
 * Every stroke lands **black, as ink** (Greg, 2026-10-06: converted strokes land in the ink
 * layer): the style is the pen's and the colour black, so the engine's own routing puts them in
 * the ink raster and the rubber never reaches them. A page with no strokes comes across blank,
 * papered as it was; a page the envelope names with no usable size is skipped.
 */
object InkIngest {

    /** The file extension Soil routes to this app: the notebook's ink envelope. */
    const val EXTENSION = "soilink"

    /** What Soil's Import flow calls files of this kind. */
    const val LABEL = "Notebook ink (.soilink)"

    /** One page to make: its size, its paper's token and picture (or none), its strokes as they
     *  are to be baked. */
    class Page(val width: Float, val height: Float, val paperToken: String?, val paper: ByteArray?, val strokes: List<Stroke>)

    /** The pages of [env], in order; null when the envelope is not a page payload or names none. */
    fun pagesOf(env: ClipEnvelope): List<Page>? {
        if (env.kind != ClipEnvelope.KIND_PAGE) return null
        val pageRows = env.rows.filter { it.type == TYPE_PAGE }
        if (pageRows.isEmpty()) return null
        val templates = env.rows.filter { it.type == TYPE_TEMPLATE }.associateBy { it.id }
        val all = InkClip.strokesOf(env).associateBy { it.id }
        val out = ArrayList<Page>(pageRows.size)
        for (row in pageRows) {
            val w = row.width ?: continue
            val h = row.height ?: continue
            if (w <= 0f || h <= 0f) continue
            val template = row.refId?.takeIf { it.isNotEmpty() }?.let { templates[it] }
            val onPage = env.rows.filter { it.type == TYPE_STROKE && it.parentId == row.id }.sortedBy { it.order }.mapNotNull { all[it.id] }
            out += Page(w, h, template?.text, template?.blobBytes(), onPage.map { it.copy(color = BLACK, style = StrokeStyle.PEN) })
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * The strokes a **Paste ink** lays on a sketch page: a lasso's or the pad's, all of them; of a
     * copied page payload, **the first page's alone**, as the pad and the calendar take a page
     * paste — every page's ink laid over one page would be a picture nobody drew.
     */
    fun strokesToPaste(env: ClipEnvelope): List<Stroke> {
        val all = InkClip.strokesOf(env)
        if (env.kind != ClipEnvelope.KIND_PAGE) return all
        val firstPage = env.rows.firstOrNull { it.type == TYPE_PAGE }?.id ?: return all
        val onFirst = env.rows.filter { it.parentId == firstPage }.mapTo(HashSet()) { it.id }
        return all.filter { it.id in onFirst }
    }

    const val BLACK: Int = 0xFF000000.toInt()
    private const val TYPE_PAGE = "page"
    private const val TYPE_TEMPLATE = "template"
    private const val TYPE_STROKE = "stroke"
}
