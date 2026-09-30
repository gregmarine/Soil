package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema.TABLE
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Statement

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
}
