package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * The **stroke half** of an ink-on-rows store — the one table every paper screen that keeps its
 * ink as rows shares, and every statement that reads or writes it ([StrokeRows.decode] pins the
 * column shape from the other end).
 *
 * A consumer composes rather than restates: its schema lists [CREATE_STROKE_TABLE] and
 * [CREATE_STROKE_INDEX] among its own tables, and its `…Sql` object delegates
 * [InkDocument.StrokeSql] here and forwards the read.
 *
 * Two rules the text carries:
 *
 * - `"order"` is **quoted** everywhere. It is a real SQLite keyword and an unquoted one is a syntax
 *   error; it is the writing order within the page, which is what makes a page's ink stable across
 *   an undo/redo cycle.
 * - a stroke row is written with `INSERT OR REPLACE` and removed with `DELETE … WHERE id = ?`, both
 *   **idempotent**: a stroke has no children, so REPLACE's delete cascades nothing, and a row that
 *   is not there is not an error.
 *
 * The `pageId` foreign key points at a table named `page` with `ON DELETE CASCADE` — which is why
 * no consumer ever writes its **page** row with `INSERT OR REPLACE`: that delete would take the
 * page's strokes with it. The cascade needs `PRAGMA foreign_keys = ON` on the connection.
 */
object InkSql : InkDocument.StrokeSql {

    // ── The table ────────────────────────────────────────────────────────────

    /** `stroke` — `StrokeCodec` format B in `blob`. */
    const val CREATE_STROKE_TABLE = """CREATE TABLE stroke (
                       id TEXT PRIMARY KEY,
                       pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                       "order" INTEGER NOT NULL,
                       color INTEGER NOT NULL,
                       width REAL NOT NULL,
                       style TEXT NOT NULL,
                       blob BLOB NOT NULL);"""

    /** Every read of a page's ink is `(pageId, "order")` ordered — the index that serves them all. */
    const val CREATE_STROKE_INDEX = """CREATE INDEX stroke_page_order ON stroke(pageId, "order");"""

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement =
        Statement(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            stroke.id, pageId, order, stroke.color.toLong(), stroke.width.toDouble(), stroke.style.name, StrokeBlob.encode(stroke),
        )

    override fun dropStroke(id: String): Statement =
        Statement("DELETE FROM stroke WHERE id = ?", id)

    /** Empty a page, keeping its row — what deleting the pad's lone page does. */
    fun clearStrokes(pageId: String): Statement =
        Statement("DELETE FROM stroke WHERE pageId = ?", pageId)

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The page's strokes, in writing order. */
    fun selectStrokes(pageId: String): Statement =
        Statement("SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? ORDER BY \"order\"", pageId)
}
