package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.Statement

/** One library item as the index describes it. */
data class Item(
    val id: String,
    val kind: String,
    val name: String,
    val keyScope: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
)

/** One link as the mirror in a file states it. */
data class LinkRow(val id: String, val pageId: String, val targetItemId: String, val targetPageId: String?)

/** One link into an item, with the source item's name and kind from the item table. */
data class Backlink(
    val linkId: String,
    val sourceItemId: String,
    val sourceKind: String,
    val sourceName: String,
    val sourcePageId: String,
    val targetPageId: String?,
)

/**
 * Every read and write of the index's rows. **Blocking**: IO only, and only while
 * [SoilIndex.isReady].
 */
class IndexStore(private val rows: SqlCipherRowStore = SqlCipherRowStore(SoilIndex.db())) {

    /** Every item that has not been deleted, newest first. */
    fun aliveItems(): List<Item> =
        rows.query(Statement("$SELECT WHERE deletedAt IS NULL ORDER BY updatedAt DESC, id")).rows.map(::item)

    /** Every item of [kind] that has not been deleted, newest first. */
    fun aliveItems(kind: String): List<Item> =
        rows.query(
            Statement("$SELECT WHERE deletedAt IS NULL AND kind = ? ORDER BY updatedAt DESC, id", kind),
        ).rows.map(::item)

    /** The items of [kind] opened most recently, latest first. */
    fun recentItems(kind: String, limit: Int): List<Item> =
        rows.query(
            Statement(
                "$SELECT WHERE deletedAt IS NULL AND kind = ? AND openedAt IS NOT NULL ORDER BY openedAt DESC, id LIMIT ?",
                kind, limit,
            ),
        ).rows.map(::item)

    fun markOpened(itemId: String, at: Long) {
        rows.exec(listOf(Statement("UPDATE item SET openedAt = ? WHERE id = ?", at, itemId)))
    }

    /** The item by [itemId], or null when there is none alive. */
    fun aliveItem(itemId: String): Item? =
        rows.query(Statement("$SELECT WHERE deletedAt IS NULL AND id = ?", itemId)).rows.firstOrNull()?.let(::item)

    /** A new item under the global key. The id is never one that was used before. */
    fun insert(id: String, kind: String, name: String, now: Long) {
        rows.exec(
            listOf(
                Statement(
                    "INSERT INTO item (id, kind, name, keyScope, flags, createdAt, updatedAt) VALUES (?, ?, ?, ?, 0, ?, ?)",
                    id, kind, name, IndexSchema.KEY_SCOPE_GLOBAL, now, now,
                ),
            ),
        )
    }

    /** False when there is no such item alive. */
    fun rename(itemId: String, name: String, now: Long): Boolean =
        rows.exec(
            listOf(
                Statement("UPDATE item SET name = ?, updatedAt = ? WHERE id = ? AND deletedAt IS NULL", name, now, itemId),
            ),
        )[0] > 0

    /** A delete is soft: the row is marked, and the file is not touched. */
    fun softDelete(itemId: String, now: Long): Boolean =
        rows.exec(
            listOf(Statement("UPDATE item SET deletedAt = ? WHERE id = ? AND deletedAt IS NULL", now, itemId)),
        )[0] > 0

    fun setPageCount(itemId: String, count: Int) {
        rows.exec(listOf(Statement("UPDATE item SET pageCount = ? WHERE id = ?", count, itemId)))
    }

    /** The item was written to at [at]. Never moved backwards. */
    fun touch(itemId: String, at: Long) {
        rows.exec(listOf(Statement("UPDATE item SET updatedAt = ? WHERE id = ? AND updatedAt < ?", at, itemId, at)))
    }

    // ── Links ──────

    /** Make the index's links from [itemId] exactly [links], in one transaction. */
    fun replaceLinks(itemId: String, links: List<LinkRow>) {
        val statements = ArrayList<Statement>(links.size + 1)
        statements += Statement("DELETE FROM link WHERE sourceItemId = ?", itemId)
        for (l in links) {
            statements += Statement(
                "INSERT OR REPLACE INTO link (id, sourceItemId, sourcePageId, targetItemId, targetPageId) VALUES (?, ?, ?, ?, ?)",
                l.id, itemId, l.pageId, l.targetItemId, l.targetPageId,
            )
        }
        rows.exec(statements)
    }

    /** Every link into [targetItemId] from an item that is alive, by source name then page. */
    fun backlinks(targetItemId: String): List<Backlink> =
        rows.query(
            Statement(
                "SELECT l.id, l.sourceItemId, i.kind, i.name, l.sourcePageId, l.targetPageId FROM link l " +
                    "JOIN item i ON i.id = l.sourceItemId " +
                    "WHERE l.targetItemId = ? AND i.deletedAt IS NULL ORDER BY i.name, l.sourcePageId, l.id",
                targetItemId,
            ),
        ).rows.map {
            Backlink(
                linkId = it.text("id"), sourceItemId = it.text("sourceItemId"), sourceKind = it.text("kind"),
                sourceName = it.text("name"), sourcePageId = it.text("sourcePageId"), targetPageId = it.textOrNull("targetPageId"),
            )
        }

    private fun item(row: com.symmetricalpalmtree.soil.paper.store.Row) = Item(
        id = row.text("id"),
        kind = row.text("kind"),
        name = row.text("name"),
        keyScope = row.text("keyScope"),
        createdAt = row.long("createdAt"),
        updatedAt = row.long("updatedAt"),
        pageCount = row.long("pageCount").toInt(),
    )

    /** The items the global key opens — what a rotation re-keys. */
    fun globalItems(): List<Item> = aliveItems().filter { it.keyScope == IndexSchema.KEY_SCOPE_GLOBAL }

    /**
     * An item that opens under neither key of a rotation: it is marked as having a passphrase of
     * its own, so it is asked for when opened rather than failing under the global key. The file
     * is not touched.
     */
    fun quarantine(itemId: String) {
        rows.exec(
            listOf(
                Statement(
                    "UPDATE item SET keyScope = ?, updatedAt = ? WHERE id = ?",
                    IndexSchema.KEY_SCOPE_ITEM, System.currentTimeMillis(), itemId,
                ),
            ),
        )
    }

    private companion object {
        const val SELECT = "SELECT id, kind, name, keyScope, createdAt, updatedAt, pageCount FROM item"
    }
}
