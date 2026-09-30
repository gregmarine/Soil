package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.soil.seam.SeamSchema

/**
 * **What a notebook file holds**: one universal table, `notebook`, where every thing in the
 * notebook is a row and what kind of thing it is, is its [type][TYPE_PAGE]. It is Notesprout SN's
 * table, column for column, without the sketch and document rows: a notebook holds ink and what
 * is placed among it, and nothing else.
 *
 * ```
 * notebook (parentId "")            text = title · refId = the page last open
 *   template                        text = token · width/height px · blob = the image
 *   page                            refId = its template's row id ("" = blank) · width/height px
 *     stroke · heading · text · shape
 *     link                          the strokes and headings it wraps are re-parented to it
 *     sticky_note                   its content is stroke rows parented to it, in its own space
 * ```
 *
 * A new kind of thing is a new row type, never a new column and never a new step: the version
 * moves only when the table itself has to.
 *
 * `"order"` is an SQL keyword and is always double-quoted. It is a position among the rows of the
 * same parent **and** the same type, dense from 0. A delete is soft; [PURGE] is what Soil runs
 * when the notebook is closed for good.
 */
object NotebookSchema {

    const val KIND = "notebook"
    const val TABLE = "notebook"

    const val TYPE_NOTEBOOK = "notebook"
    const val TYPE_PAGE = "page"
    const val TYPE_TEMPLATE = "template"
    const val TYPE_STROKE = "stroke"
    const val TYPE_HEADING = "heading"
    const val TYPE_LINK = "link"
    const val TYPE_TEXT = "text"
    const val TYPE_SHAPE = "shape"
    const val TYPE_STICKY = "sticky_note"

    /** The notebook row's `parentId`: it is the root. */
    const val ROOT_PARENT = ""

    /** Every column, in the table's order. */
    val COLUMNS: List<String> = listOf(
        "id", "parentId", "type", "\"order\"", "createdAt", "updatedAt", "deletedAt", "text", "refId",
        "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob",
    )

    private val V1 = listOf(
        """CREATE TABLE notebook (
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
        """CREATE INDEX idx_notebook_parent_order ON notebook(parentId, "order", deletedAt)""",
    )

    /**
     * Every soft-deleted row, and every row under one, goes. Three rules, each one a lesson:
     *
     *  - **Template rows are exempt**, on both sides. Nothing soft-deletes a template, and a page
     *    that is alive may still point at it.
     *  - **The cascade starts from what is deleted, never from "its parent is missing".** A row
     *    whose parent is not in the file at all is a sign of damage, and is left where it is.
     *  - **`updatedAt` is not touched.** Rows are removed, never rewritten.
     */
    const val PURGE =
        "WITH RECURSIVE doomed(id) AS (" +
            "SELECT id FROM notebook WHERE deletedAt IS NOT NULL AND type != 'template' " +
            "UNION " +
            "SELECT n.id FROM notebook n JOIN doomed d ON n.parentId = d.id WHERE n.type != 'template'" +
            ") DELETE FROM notebook WHERE id IN (SELECT id FROM doomed)"

    val SCHEMA = SeamSchema(kind = KIND, steps = listOf(V1), purge = listOf(PURGE))
}
