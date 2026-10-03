package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.Row
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.util.UUID

/** A folder of the library. `parentId` is `''` at the root. */
data class Folder(val id: String, val parentId: String, val name: String, val createdAt: Long, val updatedAt: Long)

/** What a folder says of what is made inside it; null where it says nothing. */
data class FolderPrefs(val scheme: String?, val template: String?)

/**
 * The library's shape over the index: folders, where items sit, the pins, the covers, and each
 * folder's say. Listings are **blob-free**; [cover] is the one read that costs bytes. **Blocking**:
 * IO only, while [SoilIndex.isReady].
 *
 * Folder names are unique among sibling folders, compared exactly, which the caller checks with
 * [folderNameTaken]. Item names are the person's own words and are never refused for a twin.
 */
class LibraryStore(private val rows: SqlCipherRowStore = SqlCipherRowStore(SoilIndex.db())) {

    // ── Folders ──────

    fun folders(parentId: String): List<Folder> =
        rows.query(Statement("$FOLDER_SELECT WHERE deletedAt IS NULL AND parentId = ? ORDER BY name", parentId)).rows.map(::folder)

    fun folder(id: String): Folder? =
        rows.query(Statement("$FOLDER_SELECT WHERE deletedAt IS NULL AND id = ?", id)).rows.firstOrNull()?.let(::folder)

    fun allFolders(): List<Folder> =
        rows.query(Statement("$FOLDER_SELECT WHERE deletedAt IS NULL ORDER BY name")).rows.map(::folder)

    /** Root first, down to [folderId] itself. Empty for the root. Cycle-guarded. */
    fun ancestry(folderId: String): List<Folder> {
        val out = ArrayList<Folder>()
        var at: String? = folderId
        val seen = HashSet<String>()
        while (!at.isNullOrEmpty() && seen.add(at)) {
            val f = folder(at) ?: break
            out += f
            at = f.parentId
        }
        return out.reversed()
    }

    fun folderNameTaken(parentId: String, name: String, exceptId: String? = null): Boolean =
        rows.query(
            Statement("SELECT count(*) AS n FROM folder WHERE deletedAt IS NULL AND parentId = ? AND name = ? AND id != ?", parentId, name, exceptId ?: ""),
        ).rows.first().long("n") > 0

    fun createFolder(name: String, parentId: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        rows.exec(listOf(Statement("INSERT INTO folder (id, parentId, name, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)", id, parentId, name, now, now)))
        return id
    }

    fun renameFolder(id: String, name: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("UPDATE folder SET name = ?, updatedAt = ? WHERE id = ?", name, now, id)))
    }

    fun moveFolder(id: String, parentId: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("UPDATE folder SET parentId = ?, updatedAt = ? WHERE id = ?", parentId, now, id)))
    }

    /** Whether [folderId] is [movingId] or sits under it: where a folder may never be moved. */
    fun isSelfOrDescendant(folderId: String, movingId: String): Boolean =
        folderId == movingId || ancestry(folderId).any { it.id == movingId }

    /**
     * Soft-delete the folder and everything under it at any depth, the pins and the say of each
     * folder with it, in one transaction. Answers the ids of the items that went: the caller
     * takes their files away.
     */
    fun deleteFolderRecursive(id: String, now: Long = System.currentTimeMillis()): List<String> {
        val folders = ArrayList<String>()
        val items = ArrayList<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val fid = stack.removeLast()
            if (!seen.add(fid)) continue
            folders += fid
            items += itemIdsIn(fid)
            folders(fid).forEach { stack.add(it.id) }
        }
        val statements = ArrayList<Statement>()
        for (f in folders) {
            statements += Statement("UPDATE folder SET deletedAt = ? WHERE id = ? AND deletedAt IS NULL", now, f)
            statements += Statement("DELETE FROM folder_prefs WHERE folderId = ?", f)
        }
        for (i in items) {
            statements += Statement("UPDATE item SET deletedAt = ?, cover = NULL WHERE id = ? AND deletedAt IS NULL", now, i)
            statements += Statement("DELETE FROM item_pin WHERE id = ?", i)
            statements += IndexStore.forgetItem(i)
        }
        rows.exec(statements)
        return items
    }

    // ── Items ──────

    /** The alive items in [parentId], blob-free, newest first. */
    fun items(parentId: String): List<Item> =
        rows.query(Statement("$ITEM_SELECT WHERE deletedAt IS NULL AND parentId = ? ORDER BY updatedAt DESC, id", parentId)).rows.map(::item)

    fun allItems(): List<Item> =
        rows.query(Statement("$ITEM_SELECT WHERE deletedAt IS NULL ORDER BY name")).rows.map(::item)

    fun item(id: String): Item? =
        rows.query(Statement("$ITEM_SELECT WHERE deletedAt IS NULL AND id = ?", id)).rows.firstOrNull()?.let(::item)

    fun aliveItems(ids: Collection<String>): Map<String, Item> =
        ids.distinct().chunked(400).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            rows.query(Statement("$ITEM_SELECT WHERE deletedAt IS NULL AND id IN ($marks)", *chunk.toTypedArray())).rows.map(::item)
        }.associateBy { it.id }

    private fun itemIdsIn(parentId: String): List<String> =
        rows.query(Statement("SELECT id FROM item WHERE deletedAt IS NULL AND parentId = ?", parentId)).rows.map { it.text("id") }

    fun moveItem(id: String, parentId: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("UPDATE item SET parentId = ?, updatedAt = ? WHERE id = ?", parentId, now, id)))
    }

    /** Soft-delete one item, its cover, its pin, its tags and its page order with it. False when it was not alive. */
    fun deleteItem(id: String, now: Long = System.currentTimeMillis()): Boolean =
        rows.exec(
            listOf(
                Statement("UPDATE item SET deletedAt = ?, cover = NULL WHERE id = ? AND deletedAt IS NULL", now, id),
                Statement("DELETE FROM item_pin WHERE id = ?", id),
            ) + IndexStore.forgetItem(id),
        )[0] > 0

    /** The item's cover, or null when it has none. */
    fun cover(id: String): ByteArray? =
        rows.query(Statement("SELECT cover FROM item WHERE deletedAt IS NULL AND id = ?", id)).rows.firstOrNull()?.blobOrNull("cover")

    /** A cover is a picture, not an edit: `updatedAt` is left alone. */
    fun setCover(id: String, cover: ByteArray) {
        rows.exec(listOf(Statement("UPDATE item SET cover = ? WHERE id = ? AND deletedAt IS NULL", cover, id)))
    }

    // ── Pins ──────

    fun pinnedIds(): List<String> = rows.query(Statement("SELECT id FROM item_pin ORDER BY pinnedAt")).rows.map { it.text("id") }

    fun pin(id: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("INSERT OR IGNORE INTO item_pin (id, pinnedAt) VALUES (?, ?)", id, now)))
    }

    fun unpin(id: String) {
        rows.exec(listOf(Statement("DELETE FROM item_pin WHERE id = ?", id)))
    }

    // ── A folder's say: the naming scheme and the default template ──────

    fun folderPrefs(folderId: String): FolderPrefs =
        rows.query(Statement("SELECT scheme, template FROM folder_prefs WHERE folderId = ?", folderId)).rows.firstOrNull()
            ?.let { FolderPrefs(it.textOrNull("scheme"), it.textOrNull("template")) } ?: FolderPrefs(null, null)

    fun setScheme(folderId: String, scheme: String?) {
        rows.exec(listOf(Statement("INSERT INTO folder_prefs (folderId, scheme) VALUES (?, ?) ON CONFLICT(folderId) DO UPDATE SET scheme = excluded.scheme", folderId, scheme)))
    }

    fun setDefaultTemplate(folderId: String, pick: String?) {
        rows.exec(listOf(Statement("INSERT INTO folder_prefs (folderId, template) VALUES (?, ?) ON CONFLICT(folderId) DO UPDATE SET template = excluded.template", folderId, pick)))
    }

    /**
     * The nearest say up the tree: the folder's own, then each ancestor's, then the root's.
     * [pick] reads one field of a folder's prefs; the first folder that answers wins.
     */
    fun <T> resolve(folderId: String, pick: (FolderPrefs) -> T?): T? {
        val chain = (if (folderId.isEmpty()) emptyList() else ancestry(folderId).map { it.id }.reversed()) + ""
        for (id in chain) pick(folderPrefs(id))?.let { return it }
        return null
    }

    private fun folder(row: Row) = Folder(row.text("id"), row.text("parentId"), row.text("name"), row.long("createdAt"), row.long("updatedAt"))

    private fun item(row: Row) = Item(
        id = row.text("id"), kind = row.text("kind"), name = row.text("name"), keyScope = row.text("keyScope"),
        createdAt = row.long("createdAt"), updatedAt = row.long("updatedAt"), pageCount = row.long("pageCount").toInt(),
        parentId = row.text("parentId"), openedAt = row.longOrNull("openedAt"),
    )

    private companion object {
        const val FOLDER_SELECT = "SELECT id, parentId, name, createdAt, updatedAt FROM folder"
        const val ITEM_SELECT = "SELECT id, kind, name, keyScope, createdAt, updatedAt, pageCount, parentId, openedAt FROM item"
    }
}
