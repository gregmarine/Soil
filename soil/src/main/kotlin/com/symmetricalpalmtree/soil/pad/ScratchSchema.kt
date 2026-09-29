package com.symmetricalpalmtree.soil.pad

import com.symmetricalpalmtree.soil.data.Schema
import com.symmetricalpalmtree.soil.paper.ink.InkSql

/**
 * The Scratch Pad's tables, in its own store — `garden/scratchpad.db`, under the global key.
 *
 * ```sql
 * page   (id, position, width, height, createdAt, updatedAt)   -- 0 × 0 = size not learned yet
 * stroke (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
 * state  (key, value)                                          -- 'current' → the current page id
 * ```
 *
 * **The `stroke` half is `:paper`'s** ([InkSql.CREATE_STROKE_TABLE] / [InkSql.CREATE_STROKE_INDEX])
 * — one declaration for every screen that keeps ink as rows; only the pad's own tables are
 * written here.
 *
 * `stroke.blob` is `StrokeCodec` format B (x / y / pressure / tilt). `stroke."order"` is the
 * writing order within its page and is what makes the page's ink stable across an undo/redo cycle.
 *
 * Foreign keys are ON for the connection, so `DELETE FROM page` takes that page's strokes with it
 * — which is why a page row is never written with `INSERT OR REPLACE` (REPLACE deletes the
 * conflicting row first, and that delete cascades).
 */
object ScratchSchema {

    /** A landed step is never edited — a change is a new step. */
    private val V1 = listOf(
        """CREATE TABLE page (
               id TEXT PRIMARY KEY,
               position INTEGER NOT NULL,
               width REAL NOT NULL,
               height REAL NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL);""",
        "CREATE INDEX page_position ON page(position);",
        InkSql.CREATE_STROKE_TABLE,
        InkSql.CREATE_STROKE_INDEX,
        "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
    )

    val SCHEMA = Schema("scratchpad", listOf(V1))
}
