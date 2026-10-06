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
    /** The folder it is in; `''` at the root. */
    val parentId: String = "",
    /** When it was last opened; null for one never opened. */
    val openedAt: Long? = null,
    /** The library's own bits about the item: [ItemFlags]. Never bumps `updatedAt`. */
    val flags: Int = 0,
)

/** The bits in an item's `flags`. */
object ItemFlags {
    /** Set by the person: the backup run counts the item as skipped and never copies it. */
    const val EXCLUDE_FROM_BACKUP = 1
}

/** One link as the mirror in a file states it. */
/**
 * One row of a file's link mirror, as read: a link to an item, or ([bibleWire] non-null) one
 * range of a link into the Bible, whose [targetItemId] is `''`.
 */
data class LinkRow(
    val id: String,
    val pageId: String,
    val targetItemId: String,
    val targetPageId: String?,
    val bibleWire: String? = null,
    val bibleStart: Int? = null,
    val bibleEnd: Int? = null,
) {
    val isBible: Boolean get() = bibleWire != null
}

/** One link into the Bible, with the source item's name and kind from the item table, and the
 *  page's number from the page order (0 for an item with no pages: a document). */
data class BibleBacklink(
    val linkId: String,
    val sourceItemId: String,
    val sourceKind: String,
    val sourceName: String,
    val sourcePageId: String,
    val pageNumber: Int,
    val wire: String,
    val startKey: Int,
    val endKey: Int,
)

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

    /** A new item under the global key, in [parentId] (`''` = the root). The id is never one that was used before. */
    fun insert(id: String, kind: String, name: String, now: Long, parentId: String = "") {
        rows.exec(
            listOf(
                Statement(
                    "INSERT INTO item (id, kind, name, keyScope, flags, createdAt, updatedAt, parentId) VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
                    id, kind, name, IndexSchema.KEY_SCOPE_GLOBAL, now, now, parentId,
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

    /** A delete is soft: the row is marked, and the file is not touched. What the library held
     *  for the item (its tags, its page order) goes with it. */
    fun softDelete(itemId: String, now: Long): Boolean =
        rows.exec(
            listOf(Statement("UPDATE item SET deletedAt = ? WHERE id = ? AND deletedAt IS NULL", now, itemId)) + forgetItem(itemId),
        )[0] > 0

    fun setPageCount(itemId: String, count: Int) {
        rows.exec(listOf(Statement("UPDATE item SET pageCount = ? WHERE id = ?", count, itemId)))
    }

    // ── Pages ──────

    /** Make the index's page order for [itemId] exactly [pageIds], and the count with it. One transaction. */
    fun setPages(itemId: String, pageIds: List<String>) {
        val statements = ArrayList<Statement>(pageIds.size + 2)
        statements += Statement("DELETE FROM item_page WHERE itemId = ?", itemId)
        pageIds.forEachIndexed { i, pageId ->
            statements += Statement("INSERT OR REPLACE INTO item_page (itemId, pageId, position) VALUES (?, ?, ?)", itemId, pageId, i.toLong())
        }
        statements += Statement("UPDATE item SET pageCount = ? WHERE id = ?", pageIds.size, itemId)
        rows.exec(statements)
    }

    /** The item's pages in order, as its app last told them. */
    fun pagesOf(itemId: String): List<String> =
        rows.query(Statement("SELECT pageId FROM item_page WHERE itemId = ? ORDER BY position", itemId)).rows.map { it.text("pageId") }

    /** Each page's 1-based number, for the pages the index knows. */
    fun pageNumbers(itemId: String): Map<String, Int> =
        rows.query(Statement("SELECT pageId, position FROM item_page WHERE itemId = ?", itemId)).rows
            .associate { it.text("pageId") to it.long("position").toInt() + 1 }

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
                "INSERT OR REPLACE INTO link (id, sourceItemId, sourcePageId, targetItemId, targetPageId, bibleWire, bibleStart, bibleEnd) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                l.id, itemId, l.pageId, l.targetItemId, l.targetPageId, l.bibleWire, l.bibleStart?.toLong(), l.bibleEnd?.toLong(),
            )
        }
        rows.exec(statements)
    }

    /**
     * Every link into the verses `startKey..endKey` from an item that is alive: the classic
     * two-comparison overlap, `bibleStart <= endKey AND bibleEnd >= startKey`, in reading order
     * then by source, at most [limit]. What the reader's Notes panel shows.
     */
    fun bibleBacklinks(startKey: Int, endKey: Int, limit: Int = 500): List<BibleBacklink> =
        rows.query(
            Statement(
                "SELECT l.id, l.sourceItemId, i.kind, i.name, l.sourcePageId, l.bibleWire, l.bibleStart, l.bibleEnd, p.position FROM link l " +
                    "JOIN item i ON i.id = l.sourceItemId " +
                    "LEFT JOIN item_page p ON p.itemId = l.sourceItemId AND p.pageId = l.sourcePageId " +
                    "WHERE l.bibleStart <= ? AND l.bibleEnd >= ? AND i.deletedAt IS NULL " +
                    "ORDER BY l.bibleStart, i.name, l.sourcePageId, l.id LIMIT ?",
                endKey.toLong(), startKey.toLong(), limit.toLong(),
            ),
        ).rows.map {
            BibleBacklink(
                linkId = it.text("id"), sourceItemId = it.text("sourceItemId"), sourceKind = it.text("kind"),
                sourceName = it.text("name"), sourcePageId = it.text("sourcePageId"),
                pageNumber = it.longOrNull("position")?.toInt()?.plus(1) ?: 0,
                wire = it.text("bibleWire"), startKey = it.long("bibleStart").toInt(), endKey = it.long("bibleEnd").toInt(),
            )
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
        parentId = row.text("parentId"),
        openedAt = row.longOrNull("openedAt"),
        flags = row.long("flags").toInt(),
    )

    /** The exclude bit, set or cleared. `updatedAt` is untouched: it is the needs-backup clock. */
    fun setExcludedFromBackup(itemId: String, excluded: Boolean) {
        rows.exec(listOf(Statement(
            "UPDATE item SET flags = (flags & ~?) | ? WHERE id = ?",
            ItemFlags.EXCLUDE_FROM_BACKUP, if (excluded) ItemFlags.EXCLUDE_FROM_BACKUP else 0, itemId,
        )))
    }

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

    companion object {
        private const val SELECT = "SELECT id, kind, name, keyScope, createdAt, updatedAt, pageCount, parentId, openedAt, flags FROM item"

        /** The statements that drop what the library holds for an item: its assignments and its pages. */
        fun forgetItem(itemId: String): List<Statement> = listOf(
            Statement("DELETE FROM tag_assignment WHERE itemId = ?", itemId),
            Statement("DELETE FROM item_page WHERE itemId = ?", itemId),
        )
    }
}
