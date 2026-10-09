package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.seam.SeamSchema

/**
 * **What a sketchbook file holds**: one universal table, `sketchbook`, with the notebook table's
 * columns, column for column — so the page, template and order SQL is Notesprout's by table name
 * and the export renderer's relabel keeps its shape — and a sketchbook's own row types in it.
 *
 * ```
 * sketchbook (parentId "")          text = title · refId = the page last open
 *   template                        text = token · width/height px · blob = the image
 *   page                            refId = its template's row id ("" = blank) · width/height px
 *     sketch_graphite               "order" −1 · blob = a page-sized lossless WebP with alpha
 *     sketch_ink                    "order" −1 · blob = the same, for the gel pen's layer
 *     sketch_marker                 "order" −1 · blob = the same, for the marker's layer
 *     guide_grid                    text = JSON (phase 5)
 *     guide_image                   blob = a lossy WebP · text = JSON (phase 5)
 * ```
 *
 * A page is **three rasters, one picture** (Notesprout SN's arc 45, the marker since 2026-10-08):
 * the pencil's graphite, which the rubber rubs, the gel pen's ink, which nothing erases, and the
 * marker's translucent layer over both. Each is its own row, written whole and replaced in place;
 * a blank layer has no row at all. `"order"` is −1 for all three, out of the marks' stacking
 * space: no image is a mark.
 *
 * A new kind of thing is a new row type, never a new column and never a new step. `"order"` is an
 * SQL keyword and is always double-quoted. A delete is soft; [PURGE] is what Soil runs when the
 * sketchbook is closed for good.
 */
object SketchbookSchema {

    const val KIND = "sketchbook"
    const val TABLE = "sketchbook"

    const val TYPE_SKETCHBOOK = "sketchbook"
    const val TYPE_PAGE = "page"
    const val TYPE_TEMPLATE = "template"
    const val TYPE_SKETCH_GRAPHITE = "sketch_graphite"
    const val TYPE_SKETCH_INK = "sketch_ink"
    /** The marker's raster (2026-10-08) — a third row of the same shape; no schema step, a type is
     *  a plain column, and a build before it never asks for the row. */
    const val TYPE_SKETCH_MARKER = "sketch_marker"
    const val TYPE_GUIDE_GRID = "guide_grid"
    const val TYPE_GUIDE_IMAGE = "guide_image"

    /** The sketchbook row's `parentId`: it is the root. */
    const val ROOT_PARENT = ""

    /** Every raster's `"order"`: out of the marks' stacking space, and equal because neither is
     *  above the other in the file (the flatten order is the engine's). */
    const val SKETCH_ORDER = -1

    /** Every column, in the table's order — the notebook table's. */
    val COLUMNS: List<String> = listOf(
        "id", "parentId", "type", "\"order\"", "createdAt", "updatedAt", "deletedAt", "text", "refId",
        "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob",
    )

    private val V1 = listOf(
        """CREATE TABLE sketchbook (
               id          TEXT    NOT NULL PRIMARY KEY,
               parentId    TEXT    NOT NULL,
               type        TEXT    NOT NULL,
               "order"     INTEGER NOT NULL DEFAULT 0,
               createdAt   INTEGER NOT NULL,
               updatedAt   INTEGER NOT NULL,
               deletedAt   INTEGER,
               text        TEXT,
               refId       TEXT,
               x           REAL,
               y           REAL,
               width       REAL,
               height      REAL,
               color       TEXT,
               strokeWidth REAL,
               style       TEXT,
               flags       INTEGER,
               blob        BLOB)""",
        """CREATE INDEX idx_sketchbook_parent_order ON sketchbook(parentId, "order", deletedAt)""",
    )

    /** Every soft-deleted row, and every row under one, goes — the notebook's three rules:
     *  templates are exempt on both sides, the cascade starts from what is deleted, and
     *  `updatedAt` is not touched. */
    const val PURGE =
        "WITH RECURSIVE doomed(id) AS (" +
            "SELECT id FROM sketchbook WHERE deletedAt IS NOT NULL AND type != 'template' " +
            "UNION " +
            "SELECT n.id FROM sketchbook n JOIN doomed d ON n.parentId = d.id WHERE n.type != 'template'" +
            ") DELETE FROM sketchbook WHERE id IN (SELECT id FROM doomed)"

    val SCHEMA = SeamSchema(kind = KIND, steps = listOf(V1), purge = listOf(PURGE))
}
