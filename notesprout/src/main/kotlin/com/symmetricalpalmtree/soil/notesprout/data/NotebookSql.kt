package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.LinkTarget
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.notesprout.objects.StickyFlags
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema.TABLE
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLinks

/**
 * Every statement Notesprout sends for a notebook, as a pure builder: SQL text and bound
 * arguments and nothing else, so the shapes are JVM-tested without a database, and every one
 * passes the seam's checker.
 *
 * Every write is **idempotent**, so a write that failed and is retried converges:
 *
 * - a stroke row is put with an upsert that keeps `createdAt`, and taken away by **soft delete**
 *   (`deletedAt`), never a `DELETE`: the purge at close is what removes rows for good, and an
 *   undo in between puts the stroke back at the order it held;
 * - a page row is made with `INSERT OR IGNORE` and then `UPDATE`d. A page has children, so a
 *   `REPLACE` of it would be a delete of its ink in another name.
 *
 * `"order"` is quoted everywhere: it is an SQL keyword. `now` is passed in so a test can pin it.
 */
object NotebookSql : InkDocument.StrokeSql {

    // ── The notebook row ──────

    fun insertRoot(notebookId: String, name: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text) VALUES (?, '', 'notebook', 0, ?, ?, ?)",
        notebookId, now, now, name,
    )

    fun selectRoot(notebookId: String): Statement =
        Statement("SELECT refId FROM $TABLE WHERE id = ? AND type = 'notebook' AND deletedAt IS NULL", notebookId)

    /** The page last open, so a reopen lands on it. */
    fun setLastOpened(notebookId: String, pageId: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET refId = ?, updatedAt = ? WHERE id = ? AND type = 'notebook'", pageId, now, notebookId)

    /** The file's own title, kept in step with the library. */
    fun setTitle(notebookId: String, name: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET text = ?, updatedAt = ? WHERE id = ? AND type = 'notebook'", name, now, notebookId)

    // ── Pages ──────

    fun selectPages(notebookId: String): Statement = Statement(
        "SELECT id, \"order\", width, height, refId FROM $TABLE WHERE parentId = ? AND type = 'page' AND deletedAt IS NULL ORDER BY \"order\"",
        notebookId,
    )

    /** [templateId] is the page's paper: a template row's id, or `""` for blank. */
    fun insertPage(id: String, notebookId: String, order: Int, width: Float, height: Float, templateId: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, refId, width, height) VALUES (?, ?, 'page', ?, ?, ?, ?, ?, ?)",
        id, notebookId, order.toLong(), now, now, templateId, width.toDouble(), height.toDouble(),
    )

    fun setOrder(id: String, order: Int, now: Long): Statement =
        Statement("UPDATE $TABLE SET \"order\" = ?, updatedAt = ? WHERE id = ?", order.toLong(), now, id)

    /** A soft delete: the row stays, marked, until the notebook is closed for good. */
    fun softDelete(id: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET deletedAt = ? WHERE id = ? AND deletedAt IS NULL", now, id)

    fun restore(id: String): Statement =
        Statement("UPDATE $TABLE SET deletedAt = NULL WHERE id = ?", id)

    /**
     * Everything alive under a page, at any depth: its strokes and objects, and what those hold
     * (a link's wrapped children, a sticky note's ink). What a page delete takes with it and a
     * page restore brings back.
     */
    fun selectLiveDescendantIds(pageId: String): Statement = Statement(
        "WITH RECURSIVE under(id) AS (" +
            "SELECT id FROM $TABLE WHERE parentId = ? AND deletedAt IS NULL " +
            "UNION SELECT n.id FROM $TABLE n JOIN under u ON n.parentId = u.id WHERE n.deletedAt IS NULL" +
            ") SELECT id FROM under",
        pageId,
    )

    // ── Whole rows: what the clipboard captures and what a paste writes ──────

    private const val ROW_COLUMNS = "id, parentId, type, \"order\", text, refId, x, y, width, height, color, strokeWidth, style, flags, blob"

    /** The live rows by id, every column. One `?` per id; the caller chunks under the bind cap. */
    fun selectRows(ids: List<String>): Statement {
        require(ids.isNotEmpty()) { "no ids" }
        val marks = ids.joinToString(", ") { "?" }
        return Statement("SELECT $ROW_COLUMNS FROM $TABLE WHERE id IN ($marks) AND deletedAt IS NULL", *ids.toTypedArray())
    }

    /** Everything alive under [pageId] at any depth, every column, in the order the rows were written. */
    fun selectLiveDescendantRows(pageId: String): Statement = Statement(
        "WITH RECURSIVE under(id) AS (" +
            "SELECT id FROM $TABLE WHERE parentId = ? AND deletedAt IS NULL " +
            "UNION SELECT n.id FROM $TABLE n JOIN under u ON n.parentId = u.id WHERE n.deletedAt IS NULL" +
            ") SELECT $ROW_COLUMNS FROM $TABLE WHERE id IN (SELECT id FROM under) ORDER BY createdAt, rowid",
        pageId,
    )

    /** The live children of one parent, of one type, every column, in z-order. */
    fun selectChildRows(parentId: String, type: String): Statement = Statement(
        "SELECT $ROW_COLUMNS FROM $TABLE WHERE parentId = ? AND type = ? AND deletedAt IS NULL ORDER BY \"order\"",
        parentId, type,
    )

    /** A pasted row, whole and alive, stamped now. Never a replace: a row that exists is left. */
    fun insertRow(r: NotebookRow, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE ($ROW_COLUMNS, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        r.id, r.parentId, r.type, r.order.toLong(), r.text, r.refId,
        r.x?.toDouble(), r.y?.toDouble(), r.width?.toDouble(), r.height?.toDouble(),
        r.color, r.strokeWidth?.toDouble(), r.style, r.flags, r.blob, now, now,
    )

    // ── Strokes ──────

    /**
     * The stroke row, as SN wrote it: the colour as `#RRGGBB` text, the width in px, the style's
     * name, the points as format B. A put brings a soft-deleted stroke back too, and keeps the
     * `createdAt` of a row that is already there. The two stroke statements take no `now`: the
     * document builds them out of the pen's callbacks, so the clock is read here.
     */
    override fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement = putStroke(pageId, order, stroke, System.currentTimeMillis())

    fun putStroke(pageId: String, order: Long, stroke: Stroke, now: Long): Statement = Statement(
        "INSERT INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, color, strokeWidth, style, blob) " +
            "VALUES (?, ?, 'stroke', ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(id) DO UPDATE SET parentId = excluded.parentId, \"order\" = excluded.\"order\", " +
            "updatedAt = excluded.updatedAt, deletedAt = NULL, color = excluded.color, " +
            "strokeWidth = excluded.strokeWidth, style = excluded.style, blob = excluded.blob",
        stroke.id, pageId, order, now, now,
        InkColorCodec.encode(stroke.color), stroke.width.toDouble(), stroke.style.name, StrokeBlob.encode(stroke),
    )

    /** The stroke is taken off the page, but kept until the purge: an undo can put it back. */
    override fun dropStroke(id: String): Statement = dropStroke(id, System.currentTimeMillis())

    fun dropStroke(id: String, now: Long): Statement = softDelete(id, now)

    fun selectStrokes(pageId: String): Statement = Statement(
        "SELECT id, \"order\", color, strokeWidth, style, blob FROM $TABLE WHERE parentId = ? AND type = 'stroke' AND deletedAt IS NULL ORDER BY \"order\"",
        pageId,
    )

    // ── Objects: headings, texts, shapes, sticky notes ──────

    /** The objects placed on a page, every kind in one read, each kind in its own z-order. */
    fun selectObjects(pageId: String): Statement = Statement(
        "SELECT id, type, \"order\", text, refId, x, y, width, height, flags FROM $TABLE " +
            "WHERE parentId = ? AND type IN ('heading', 'text', 'sticky_note') AND deletedAt IS NULL " +
            "ORDER BY type, \"order\"",
        pageId,
    )

    /** The highest `"order"` among [parentId]'s rows of [type], live or not; -1 with none. Order
     *  is per parent **and** type, and an order once used is never handed out again. */
    fun selectMaxOrder(parentId: String, type: String): Statement =
        Statement("SELECT COALESCE(MAX(\"order\"), -1) AS m FROM $TABLE WHERE parentId = ? AND type = ?", parentId, type)

    /** Every live heading in the notebook with the page it is on, for the Contents. A heading a
     *  link wraps hangs under the link: the hop link → page is made here, so `parentId` is always
     *  a page. */
    fun selectAllHeadings(): Statement = Statement(
        "SELECT h.id AS id, CASE WHEN p.type = 'link' THEN p.parentId ELSE h.parentId END AS parentId, h.type AS type, " +
            "h.\"order\" AS \"order\", h.text AS text, h.refId AS refId, h.x AS x, h.y AS y, h.width AS width, h.height AS height, " +
            "h.flags AS flags FROM $TABLE h LEFT JOIN $TABLE p ON p.id = h.parentId " +
            "WHERE h.type = 'heading' AND h.deletedAt IS NULL",
    )

    fun insertHeading(h: Heading, pageId: String, order: Int, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text, flags, x, y, width, height) " +
            "VALUES (?, ?, 'heading', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        h.id, pageId, order.toLong(), now, now, h.text, h.level.toLong(),
        h.x.toDouble(), h.y.toDouble(), h.width.toDouble(), h.height.toDouble(),
    )

    fun insertText(t: PageText, pageId: String, order: Int, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text, x, y, width, height) " +
            "VALUES (?, ?, 'text', ?, ?, ?, ?, ?, ?, ?, ?)",
        t.id, pageId, order.toLong(), now, now, t.text, t.x.toDouble(), t.y.toDouble(), t.width.toDouble(), t.height.toDouble(),
    )

    fun insertSticky(st: PageSticky, pageId: String, order: Int, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, x, y, width, height, flags) " +
            "VALUES (?, ?, 'sticky_note', ?, ?, ?, ?, ?, ?, ?, ?)",
        st.id, pageId, order.toLong(), now, now, st.x.toDouble(), st.y.toDouble(), st.width.toDouble(), st.height.toDouble(),
        StickyFlags.pack(st.contentW, st.contentH),
    )

    /** A finished drag: the row's stored position shifts by the same delta. */
    fun moveBy(id: String, dx: Float, dy: Float, now: Long): Statement = Statement(
        "UPDATE $TABLE SET x = x + ?, y = y + ?, updatedAt = ? WHERE id = ? AND deletedAt IS NULL",
        dx.toDouble(), dy.toDouble(), now, id,
    )

    /** An edit: the words, the level and the re-measured box. The top-left is kept. */
    fun setHeadingContent(h: Heading, now: Long): Statement = Statement(
        "UPDATE $TABLE SET text = ?, flags = ?, width = ?, height = ?, updatedAt = ? WHERE id = ?",
        h.text, h.level.toLong(), h.width.toDouble(), h.height.toDouble(), now, h.id,
    )

    fun setTextContent(t: PageText, now: Long): Statement = Statement(
        "UPDATE $TABLE SET text = ?, width = ?, height = ?, updatedAt = ? WHERE id = ?",
        t.text, t.width.toDouble(), t.height.toDouble(), now, t.id,
    )

    /** The live children of one parent, of one type, by id. */
    fun selectLiveChildIds(parentId: String, type: String): Statement =
        Statement("SELECT id FROM $TABLE WHERE parentId = ? AND type = ? AND deletedAt IS NULL", parentId, type)

    // ── Templates: a page's paper, shared rows under the notebook ──────

    /** Every template row, blob-free: what the reuse rule reads. */
    fun selectTemplateDigests(notebookId: String): Statement = Statement(
        "SELECT id, text, width, height, length(blob) AS blobLength FROM $TABLE WHERE parentId = ? AND type = 'template' AND deletedAt IS NULL",
        notebookId,
    )

    fun selectTemplateBlob(id: String): Statement =
        Statement("SELECT blob FROM $TABLE WHERE id = ? AND type = 'template' AND deletedAt IS NULL", id)

    /** Paper stored for this notebook: the token in `text`, the size it was rendered at, the pixels. */
    fun insertTemplate(id: String, notebookId: String, token: String, width: Int, height: Int, blob: ByteArray, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text, width, height, blob) VALUES (?, ?, 'template', 0, ?, ?, ?, ?, ?, ?)",
        id, notebookId, now, now, token, width.toDouble(), height.toDouble(), blob,
    )

    /** Point a page at a template row, or at nothing (`""`): the one write of a re-papering. */
    fun setPageTemplate(pageId: String, templateId: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET refId = ?, updatedAt = ? WHERE id = ? AND type = 'page'", templateId, now, pageId)

    // ── Links ──────

    /** The page's links in z-order. What each wraps is read by parent: [selectStrokes] and
     *  [selectObjects] with the link's id. */
    fun selectLinks(pageId: String): Statement = Statement(
        "SELECT id, \"order\", text, x, y, width, height FROM $TABLE WHERE parentId = ? AND type = 'link' AND deletedAt IS NULL ORDER BY \"order\"",
        pageId,
    )

    /** The link row alone; what it wraps is re-parented to it with [reparent]. */
    fun insertLink(l: PageLink, pageId: String, order: Int, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text, x, y, width, height) " +
            "VALUES (?, ?, 'link', ?, ?, ?, ?, ?, ?, ?, ?)",
        l.id, pageId, order.toLong(), now, now, LinkPayload.cap(l.payload),
        l.x.toDouble(), l.y.toDouble(), l.width.toDouble(), l.height.toDouble(),
    )

    /** A wrap moves a row under the link; an unlink moves it back under the page. Ids and
     *  coordinates are untouched: the row only changes whose it is. */
    fun reparent(id: String, parentId: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET parentId = ?, updatedAt = ? WHERE id = ?", parentId, now, id)

    /** An edit of where the link points. The wrapped content is unchanged. */
    fun setLinkPayload(id: String, payload: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET text = ?, updatedAt = ? WHERE id = ?", LinkPayload.cap(payload), now, id)

    // ── The link mirror: Soil's table, written in the same batch as the link ──────

    /** The mirror row for [l], or the drop of one it cannot have: a payload Soil cannot name
     *  points nowhere in the library. */
    fun mirror(l: PageLink, pageId: String, ownItemId: String): Statement = mirrorRow(l.id, l.payload, pageId, ownItemId)

    /** The same, for a link row a paste writes: its id, its payload, the page it lands on. */
    fun mirrorRow(linkId: String, payload: String?, pageId: String, ownItemId: String): Statement {
        val target = LinkTarget.of(payload.orEmpty(), ownItemId) ?: return mirrorDrop(linkId)
        return Statement(SeamLinks.PUT, linkId, pageId, target.itemId, target.pageId)
    }

    fun mirrorDrop(linkId: String): Statement = Statement(SeamLinks.DROP, linkId)

    fun mirrorDropPage(pageId: String): Statement = Statement(SeamLinks.DROP_PAGE, pageId)
}
