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
)

/**
 * Every read and write of the index's rows. **Blocking**: IO only, and only while
 * [SoilIndex.isReady].
 */
class IndexStore(private val rows: SqlCipherRowStore = SqlCipherRowStore(SoilIndex.db())) {

    /** Every item that has not been deleted, newest first. */
    fun aliveItems(): List<Item> =
        rows.query(
            Statement(
                "SELECT id, kind, name, keyScope, createdAt, updatedAt FROM item " +
                    "WHERE deletedAt IS NULL ORDER BY updatedAt DESC",
            ),
        ).rows.map {
            Item(
                id = it.text("id"),
                kind = it.text("kind"),
                name = it.text("name"),
                keyScope = it.text("keyScope"),
                createdAt = it.long("createdAt"),
                updatedAt = it.long("updatedAt"),
            )
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
}
