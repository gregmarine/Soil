package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema.TABLE

/**
 * Every statement Sketchsprout sends for a sketchbook, as a pure builder: SQL text and bound
 * arguments and nothing else, so the shapes are JVM-tested without a database, and every one
 * passes the seam's checker.
 *
 * Every write is **idempotent**, so a write that failed and is retried converges: a page row is
 * made with `INSERT OR IGNORE` and then `UPDATE`d; a raster row is inserted once and thereafter
 * **replaced in place** ([updateRaster]) — a save is a new picture of the same page, not a new
 * object, so a sketchbook's size is a function of what was drawn and never of how long it took.
 *
 * `"order"` is quoted everywhere: it is an SQL keyword. `now` is passed in so a test can pin it.
 */
object SketchbookSql {

    // ── The sketchbook row ──────

    fun insertRoot(sketchbookId: String, name: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text) VALUES (?, '', 'sketchbook', 0, ?, ?, ?)",
        sketchbookId, now, now, name,
    )

    fun selectRoot(sketchbookId: String): Statement =
        Statement("SELECT refId FROM $TABLE WHERE id = ? AND type = 'sketchbook' AND deletedAt IS NULL", sketchbookId)

    /** The page last open, so a reopen lands on it. */
    fun setLastOpened(sketchbookId: String, pageId: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET refId = ?, updatedAt = ? WHERE id = ? AND type = 'sketchbook'", pageId, now, sketchbookId)

    /** The file's own title, kept in step with the library. */
    fun setTitle(sketchbookId: String, name: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET text = ?, updatedAt = ? WHERE id = ? AND type = 'sketchbook'", name, now, sketchbookId)

    // ── Pages ──────

    fun selectPages(sketchbookId: String): Statement = Statement(
        "SELECT id, \"order\", width, height, refId FROM $TABLE WHERE parentId = ? AND type = 'page' AND deletedAt IS NULL ORDER BY \"order\"",
        sketchbookId,
    )

    /** [templateId] is the page's paper: a template row's id, or `""` for blank. */
    fun insertPage(id: String, sketchbookId: String, order: Int, width: Float, height: Float, templateId: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, refId, width, height) VALUES (?, ?, 'page', ?, ?, ?, ?, ?, ?)",
        id, sketchbookId, order.toLong(), now, now, templateId, width.toDouble(), height.toDouble(),
    )

    fun setOrder(id: String, order: Int, now: Long): Statement =
        Statement("UPDATE $TABLE SET \"order\" = ?, updatedAt = ? WHERE id = ?", order.toLong(), now, id)

    /** A soft delete: the row stays, marked, until the sketchbook is closed for good. */
    fun softDelete(id: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET deletedAt = ? WHERE id = ? AND deletedAt IS NULL", now, id)

    fun restore(id: String): Statement =
        Statement("UPDATE $TABLE SET deletedAt = NULL WHERE id = ?", id)

    /** Everything alive under a page, at any depth: its rasters and guides. What a page delete
     *  takes with it and a page restore brings back. */
    fun selectLiveDescendantIds(pageId: String): Statement = Statement(
        "WITH RECURSIVE under(id) AS (" +
            "SELECT id FROM $TABLE WHERE parentId = ? AND deletedAt IS NULL " +
            "UNION SELECT n.id FROM $TABLE n JOIN under u ON n.parentId = u.id WHERE n.deletedAt IS NULL" +
            ") SELECT id FROM under",
        pageId,
    )

    // ── Templates: a page's paper, shared rows under the sketchbook ──────

    /** Every template row, blob-free: what the reuse rule reads. */
    fun selectTemplateDigests(sketchbookId: String): Statement = Statement(
        "SELECT id, text, width, height, length(blob) AS blobLength FROM $TABLE WHERE parentId = ? AND type = 'template' AND deletedAt IS NULL",
        sketchbookId,
    )

    fun selectTemplateBlob(id: String): Statement =
        Statement("SELECT blob FROM $TABLE WHERE id = ? AND type = 'template' AND deletedAt IS NULL", id)

    /** Paper stored for this sketchbook: the token in `text`, the size it was rendered at, the pixels. */
    fun insertTemplate(id: String, sketchbookId: String, token: String, width: Int, height: Int, blob: ByteArray, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, text, width, height, blob) VALUES (?, ?, 'template', 0, ?, ?, ?, ?, ?, ?)",
        id, sketchbookId, now, now, token, width.toDouble(), height.toDouble(), blob,
    )

    /** Point a page at a template row, or at nothing (`""`): the one write of a re-papering. */
    fun setPageTemplate(pageId: String, templateId: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET refId = ?, updatedAt = ? WHERE id = ? AND type = 'page'", templateId, now, pageId)

    // ── The rasters ──────

    /** The one live raster row of [type] under [pageId] — its id and its picture. */
    fun selectRaster(pageId: String, type: String): Statement = Statement(
        "SELECT id, blob FROM $TABLE WHERE parentId = ? AND type = ? AND deletedAt IS NULL",
        pageId, type,
    )

    /** The one live raster row's id alone — what a write asks before it decides between an
     *  insert and a replace, without carrying the picture back for nothing. */
    fun selectRasterId(pageId: String, type: String): Statement = Statement(
        "SELECT id FROM $TABLE WHERE parentId = ? AND type = ? AND deletedAt IS NULL",
        pageId, type,
    )

    /** A layer's first picture: a new row under the page, at [SketchbookSchema.SKETCH_ORDER]. */
    fun insertRaster(id: String, pageId: String, type: String, bytes: ByteArray, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, \"order\", createdAt, updatedAt, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id, pageId, type, SketchbookSchema.SKETCH_ORDER.toLong(), now, now, bytes,
    )

    /** A layer's next picture, in the row it already has: the one write a save makes after the first. */
    fun updateRaster(id: String, bytes: ByteArray, now: Long): Statement =
        Statement("UPDATE $TABLE SET blob = ?, updatedAt = ? WHERE id = ?", bytes, now, id)
}
