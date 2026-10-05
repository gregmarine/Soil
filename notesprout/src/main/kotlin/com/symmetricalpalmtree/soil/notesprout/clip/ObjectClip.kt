package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStrokeRows
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.LinkRows
import com.symmetricalpalmtree.soil.notesprout.objects.ObjectRows
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText

/**
 * A lasso selection ⇄ clipboard payload: [PageClip]'s sibling on the same envelope. Pure.
 *
 * Three differences from a page paste, each because an object paste lands **among** rows that
 * are already there: `"order"` is rebased per type after the destination page's current maximum,
 * keeping the sequence; geometry is object-level (a stroke's is inside its blob, decoded,
 * translated and re-encoded; a box's is its columns); and where it lands is decided at paste
 * time by the caller's [ObjectPlacement]. Shared with [PageClip]: fresh ids through one map, a
 * row whose parent did not travel is dropped, and a copied link's own-notebook target is
 * re-pointed across notebooks.
 *
 * A note's content is the payload's third level, page → link → sticky → stroke, and the only rows
 * not in page space: they travel with fresh ids and **un-shifted** geometry.
 */
object ObjectClip {

    /** The rows to write in insert order, the objects the screen holds (already translated), and the
     *  ids an undo soft-deletes: the top-level rows and every child that travelled with them. */
    data class Plan(
        val rows: List<NotebookRow>,
        val strokes: List<Stroke>,
        val headings: List<Heading>,
        val links: List<PageLink>,
        val texts: List<PageText>,
        val stickies: List<PageSticky>,
        val contentIds: List<String>,
    ) {
        val bounds: Bounds?
            get() {
                var b: Bounds? = null
                for (s in strokes) b = b?.union(s.bounds) ?: s.bounds
                for (h in headings) b = b?.union(h.bounds) ?: h.bounds
                for (l in links) b = b?.union(l.bounds) ?: l.bounds
                for (t in texts) b = b?.union(t.bounds) ?: t.bounds
                for (s in stickies) b = b?.union(s.bounds) ?: s.bounds
                return b
            }

        val isEmpty: Boolean get() = strokes.isEmpty() && headings.isEmpty() && links.isEmpty() && texts.isEmpty() && stickies.isEmpty()
    }

    /** Snapshot a selection. [top] is the selected rows themselves, **first**; [children] their live
     *  children: a selected link's wrapped set, and a sticky's content strokes. Null with nothing. */
    fun capture(top: List<NotebookRow>, children: List<NotebookRow>, sourceNotebookId: String, now: Long): ClipEnvelope? {
        if (top.isEmpty()) return null
        return ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_OBJECTS, sourceNotebookId = sourceNotebookId, copiedAt = now,
            rows = (top + children).map { clipRowOf(it) },
        )
    }

    /**
     * The rows a paste onto [pageId] of [notebookId] writes, and the objects the screen then
     * holds. [baseOrder] is the page's current `MAX("order")` per type; [place] takes the
     * payload's ink extent and answers the shift. Null when nothing can be placed.
     */
    fun plan(
        env: ClipEnvelope,
        notebookId: String,
        pageId: String,
        baseOrder: (type: String) -> Int,
        newId: () -> String,
        place: (Bounds) -> ObjectPlacement.Offset,
    ): Plan? {
        val rowIds = env.rows.mapTo(HashSet()) { it.id }
        val linkIds = env.rows.filter { it.type == NotebookSchema.TYPE_LINK }.mapTo(HashSet()) { it.id }
        val stickyIds = env.rows.filter { it.type == NotebookSchema.TYPE_STICKY }.mapTo(HashSet()) { it.id }
        val placeable = env.rows.filter { it.type != NotebookSchema.TYPE_PAGE && it.type != NotebookSchema.TYPE_TEMPLATE }

        val sourceParent = sourcePageOf(placeable, rowIds) ?: return null
        val top = placeable.filter { it.parentId == sourceParent }
        if (top.isEmpty()) return null
        // A link's wrapped set is anything but another link; a note's content is strokes only.
        val children = placeable.filter { row ->
            (row.parentId in linkIds && row.type != NotebookSchema.TYPE_LINK) ||
                (row.parentId in stickyIds && row.type == NotebookSchema.TYPE_STROKE)
        }

        val decoded = HashMap<String, Stroke>(top.size + children.size)
        for (row in top + children) {
            if (row.type != NotebookSchema.TYPE_STROKE) continue
            NotebookStrokeRows.decode(row.toRow(row.id, row.parentId, row.order).toRow())?.let { decoded[row.id] = it.second }
        }
        val box = payloadBounds(top, decoded) ?: return null
        val offset = place(box)
        val dx = offset.dx
        val dy = offset.dy

        val idMap = HashMap<String, String>((top.size + children.size) * 2)
        for (row in top) idMap[row.id] = newId()
        for (row in children) idMap[row.id] = newId()

        val nextOrder = HashMap<String, Int>()
        fun rebased(type: String): Int {
            val next = nextOrder[type] ?: (baseOrder(type) + 1)
            nextOrder[type] = next + 1
            return next
        }

        val rows = ArrayList<NotebookRow>(top.size + children.size)
        val strokes = ArrayList<Stroke>()
        val headings = ArrayList<Heading>()
        val texts = ArrayList<PageText>()
        val childStrokes = HashMap<String, MutableList<Stroke>>()
        val childHeadings = HashMap<String, MutableList<Heading>>()
        val childTexts = HashMap<String, MutableList<PageText>>()
        val topStickyRows = ArrayList<NotebookRow>()
        val childStickyRows = HashMap<String, MutableList<NotebookRow>>()
        val stickyStrokes = HashMap<String, MutableList<Stroke>>()
        val contentIds = ArrayList<String>(top.size + children.size)
        val linkRows = ArrayList<NotebookRow>()

        val crossNotebook = env.sourceNotebookId.isNotBlank() && env.sourceNotebookId != notebookId
        for (row in top.sortedBy { it.order }) {
            val id = idMap.getValue(row.id)
            var out = translated(row, id, pageId, rebased(row.type), dx, dy, decoded[row.id]) ?: continue
            if (crossNotebook && out.type == NotebookSchema.TYPE_LINK) out = out.copy(text = rewriteLink(out.text, env.sourceNotebookId))
            rows += out
            contentIds += id
            when (out.type) {
                NotebookSchema.TYPE_STROKE -> NotebookStrokeRows.decode(out.toRow())?.let { strokes += it.second }
                NotebookSchema.TYPE_HEADING -> ObjectRows.toHeading(out.toRow())?.let { headings += it }
                NotebookSchema.TYPE_LINK -> linkRows += out
                NotebookSchema.TYPE_TEXT -> ObjectRows.toText(out.toRow())?.let { texts += it }
                NotebookSchema.TYPE_STICKY -> topStickyRows += out
            }
        }
        // Children keep their own order: their parent is a row that did not exist a moment ago.
        for (row in children.sortedBy { it.order }) {
            val parentId = idMap[row.parentId] ?: continue
            val id = idMap.getValue(row.id)
            val local = row.parentId in stickyIds
            val out = translated(row, id, parentId, row.order, if (local) 0f else dx, if (local) 0f else dy, decoded[row.id]) ?: continue
            rows += out
            contentIds += id
            if (local) {
                NotebookStrokeRows.decode(out.toRow())?.let { stickyStrokes.getOrPut(parentId) { ArrayList() } += it.second }
                continue
            }
            when (out.type) {
                NotebookSchema.TYPE_STROKE -> NotebookStrokeRows.decode(out.toRow())?.let { childStrokes.getOrPut(parentId) { ArrayList() } += it.second }
                NotebookSchema.TYPE_HEADING -> ObjectRows.toHeading(out.toRow())?.let { childHeadings.getOrPut(parentId) { ArrayList() } += it }
                NotebookSchema.TYPE_TEXT -> ObjectRows.toText(out.toRow())?.let { childTexts.getOrPut(parentId) { ArrayList() } += it }
                NotebookSchema.TYPE_STICKY -> childStickyRows.getOrPut(parentId) { ArrayList() } += out
            }
        }
        fun stickyOf(row: NotebookRow): PageSticky? = ObjectRows.toSticky(row.toRow())?.copy(strokes = stickyStrokes[row.id].orEmpty())
        val stickies = topStickyRows.mapNotNull { stickyOf(it) }
        val links = linkRows.mapNotNull { row ->
            LinkRows.toLink(
                row.toRow(), childStrokes[row.id].orEmpty(), childHeadings[row.id].orEmpty(), childTexts[row.id].orEmpty(),
                childStickyRows[row.id].orEmpty().mapNotNull { stickyOf(it) },
            )
        }
        val plan = Plan(rows, strokes, headings, links, texts, stickies, contentIds)
        return if (plan.isEmpty) null else plan
    }

    /** A copied link's own-notebook target, re-pointed at the notebook it came from. No self-page
     *  exception: no page travels in an objects payload. */
    fun rewriteLink(text: String?, sourceNotebookId: String): String? {
        val decoded = LinkPayload.decode(text ?: return null) ?: return text
        if (decoded.kind != LinkPayload.KIND_PAGE) return text
        val target = decoded.pageId ?: return text
        return runCatching { LinkPayload.encode(decoded.chrome, LinkPayload.KIND_ITEM_PAGE, sourceNotebookId, target) }.getOrDefault(text)
    }

    /**
     * The page the selection was copied from, inferred: every top-level row shares one parent
     * that is not in the payload. Two signals, in order, neither of which a malformed payload can
     * outvote: a link row's parent (a link is top-level by definition), else the first row
     * parented outside the payload ([capture] writes the top-level rows first).
     */
    private fun sourcePageOf(rows: List<ClipRow>, rowIds: Set<String>): String? {
        var first: String? = null
        for (row in rows) {
            if (row.parentId in rowIds) continue
            if (row.type == NotebookSchema.TYPE_LINK) return row.parentId
            if (first == null) first = row.parentId
        }
        return first
    }

    /** The payload's extent over the top-level rows: a stroke's ink extent (its bounds grown by half
     *  its width), every other kind's columns. A row that decodes to nothing contributes nothing. */
    private fun payloadBounds(top: List<ClipRow>, decoded: Map<String, Stroke>): Bounds? {
        var b: Bounds? = null
        for (row in top) {
            val r = when (row.type) {
                NotebookSchema.TYPE_STROKE -> decoded[row.id]?.let { it.bounds.inflated(it.width / 2f) }
                else -> {
                    val x = row.x; val y = row.y; val w = row.width; val h = row.height
                    if (x == null || y == null || w == null || h == null) null
                    else if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite())) null
                    else Bounds(x, y, x + w, y + h)
                }
            } ?: continue
            b = b?.union(r) ?: r
        }
        return b
    }

    /** One pasted row: fresh id, new parent, rebased order, geometry shifted. A stroke's is re-encoded
     *  from its decoded form; dropped when the blob was unusable. */
    private fun translated(row: ClipRow, id: String, parentId: String, order: Int, dx: Float, dy: Float, stroke: Stroke?): NotebookRow? {
        if (row.type == NotebookSchema.TYPE_STROKE) {
            val s = stroke ?: return null
            return NotebookRow.ofStroke(parentId, order, s.translated(dx, dy).copy(id = id))
        }
        val out = row.toRow(id, parentId, order)
        val x = out.x
        val y = out.y
        return out.copy(x = if (x != null && x.isFinite()) x + dx else x, y = if (y != null && y.isFinite()) y + dy else y)
    }
}
