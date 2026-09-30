package com.symmetricalpalmtree.soil.pad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.InkSql
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * Every statement the Scratch Pad sends, as a pure builder — SQL text and bound arguments and
 * nothing else, so the shapes are JVM-testable without a database.
 *
 * **Two write ops, both idempotent**, so a write that failed and is retried converges:
 *
 * - a stroke row is written with `INSERT OR REPLACE` (a stroke has no children, so REPLACE is safe)
 *   and removed with `DELETE … WHERE id = ?` (a row that is not there is not an error);
 * - a **page** row is created with `INSERT OR IGNORE` and then `UPDATE`d. **Never
 *   `INSERT OR REPLACE INTO page`**: REPLACE deletes the conflicting row first, and with
 *   `foreign_keys` ON that delete CASCADES — it would take the page's strokes with it.
 *
 * **The `stroke` half is `:paper`'s** ([InkSql]): the two write statements arrive as
 * [InkDocument.StrokeSql] by delegation and the read forwards. What is written out here is what
 * is the **pad's own**: its `page` table, its `state` key and the page reads.
 *
 * `now` is passed in rather than read here so a test can pin it.
 */
object ScratchSql : InkDocument.StrokeSql by InkSql {

    // ── page ──────

    fun insertPage(id: String, position: Int, width: Float, height: Float, now: Long): Statement =
        Statement(
            "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
            id, position.toLong(), width.toDouble(), height.toDouble(), now, now,
        )

    /** The page learned its size (a `0 × 0` page at first layout, or a placement's page size). */
    fun sizePage(id: String, width: Float, height: Float, now: Long): Statement =
        Statement(
            "UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?",
            width.toDouble(), height.toDouble(), now, id,
        )

    /** One page's place in the list. Renumbering is per id — page counts are tens. */
    fun position(id: String, position: Int): Statement =
        Statement("UPDATE page SET position = ? WHERE id = ?", position.toLong(), id)

    /** Drops the page **and its strokes** (the declared cascade). */
    fun deletePage(id: String): Statement =
        Statement("DELETE FROM page WHERE id = ?", id)

    /** Empties a page, keeping the row — what deleting the pad's lone page does. */
    fun clearPage(id: String): Statement = InkSql.clearStrokes(id)

    // ── state ──────

    fun setCurrent(id: String): Statement =
        Statement("INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)", id)

    // ── reads ──────

    fun selectPages(): Statement =
        Statement("SELECT id FROM page ORDER BY position")

    fun selectCurrent(): Statement =
        Statement("SELECT value FROM state WHERE key = 'current'")

    fun selectPageSize(id: String): Statement =
        Statement("SELECT width, height FROM page WHERE id = ?", id)

    /** The page's strokes, in writing order. */
    fun selectStrokes(pageId: String): Statement = InkSql.selectStrokes(pageId)

    // ── geometry ──────

    /** A stroke's points as `StrokeCodec` format B (`:paper`'s [StrokeBlob]). */
    fun geometry(stroke: Stroke): ByteArray = StrokeBlob.encode(stroke)
}
