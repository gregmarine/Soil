package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.data.Schema

/**
 * The library index, `soil.db`: one row per item in the library, and nothing of what an item
 * holds. Encrypted under the global key.
 *
 * An item is one kind of content — a notebook, a sketchbook or a document — in one `.soil` file
 * named by the row's id. Ids are stable and never reused; a delete is soft.
 *
 * Tags, links, covers and page counts are not here yet. Each arrives as a step of its own.
 */
object IndexSchema {

    const val KIND_NOTEBOOK = "notebook"
    const val KIND_SKETCHBOOK = "sketchbook"
    const val KIND_DOCUMENT = "document"

    /** Opens under the global key. */
    const val KEY_SCOPE_GLOBAL = "GLOBAL"

    /** Opens under a passphrase of its own, asked for when the item is opened. */
    const val KEY_SCOPE_ITEM = "ITEM"

    private val V1 = listOf(
        """CREATE TABLE item (
               id TEXT PRIMARY KEY,
               kind TEXT NOT NULL,
               name TEXT NOT NULL,
               keyScope TEXT NOT NULL DEFAULT 'GLOBAL',
               flags INTEGER NOT NULL DEFAULT 0,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               deletedAt INTEGER);""",
        """CREATE INDEX item_kind_alive ON item(kind, deletedAt);""",
        """CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);""",
    )

    val SCHEMA = Schema("index", listOf(V1))
}
