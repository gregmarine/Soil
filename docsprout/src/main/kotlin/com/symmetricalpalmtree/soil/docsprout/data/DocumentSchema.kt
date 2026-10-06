package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.seam.SeamSchema

/**
 * **What a document file holds**: one table, `document`, where every thing in the document is a
 * row and what kind of thing it is, is its [type][TYPE_DOCUMENT]. The notebook's lesson, kept: a
 * new kind of thing is a new row type, never a new column and never a new step.
 *
 * ```
 * document (parentId "")            the root; its id is the item's
 *   body                            text = the whole document, as Markdown
 * ```
 *
 * The document's name is not here: it is the library's, and the file's own copy is Soil's
 * (`soil_meta`). A delete is soft; [PURGE] is what Soil runs when the document is closed for good.
 */
object DocumentSchema {

    const val KIND = "document"
    const val TABLE = "document"

    const val TYPE_DOCUMENT = "document"
    const val TYPE_BODY = "body"

    /** A Bible link the writer took off: its words in `text`, its wire in `refId`, under the root.
     *  A row type, not a step: the table's shape is unchanged. */
    const val TYPE_BIBLE_UNLINKED = "bible_unlinked"

    /** The root row's `parentId`. */
    const val ROOT_PARENT = ""

    /** Every column, in the table's order. */
    val COLUMNS: List<String> = listOf("id", "parentId", "type", "createdAt", "updatedAt", "deletedAt", "text", "refId")

    private val V1 = listOf(
        """CREATE TABLE document (
               id          TEXT    NOT NULL PRIMARY KEY,
               parentId    TEXT    NOT NULL,
               type        TEXT    NOT NULL,
               createdAt   INTEGER NOT NULL,
               updatedAt   INTEGER NOT NULL,
               deletedAt   INTEGER,
               text        TEXT,
               refId       TEXT)""",
        """CREATE INDEX idx_document_parent_type ON document(parentId, type, deletedAt)""",
    )

    /** Every soft-deleted row goes. `updatedAt` is not touched: rows are removed, never rewritten. */
    const val PURGE = "DELETE FROM document WHERE deletedAt IS NOT NULL"

    val SCHEMA = SeamSchema(kind = KIND, steps = listOf(V1), purge = listOf(PURGE))
}
