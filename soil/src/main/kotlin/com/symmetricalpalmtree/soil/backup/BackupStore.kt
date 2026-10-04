package com.symmetricalpalmtree.soil.backup

import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * The backup config's one row: the `meta` table's `backup` key. Living in the index lets the stamp
 * maps ride the same encryption, the same backup and the same restore as the rows they describe.
 * Blocking; IO only, and only while the index is open.
 */
class BackupStore(private val rows: RowStore = SqlCipherRowStore(SoilIndex.db())) {

    fun read(): BackupConfig =
        BackupConfig.decode(runCatching { rows.query(Statement("SELECT value FROM meta WHERE key = ?", KEY)).rows.firstOrNull()?.text("value") }.getOrNull())

    /** False if it would not encode; nothing is written then. */
    fun write(config: BackupConfig): Boolean {
        val text = BackupConfig.encode(config) ?: return false
        rows.exec(listOf(Statement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", KEY, text)))
        return true
    }

    /** Forget one item's stamp in both maps: an import onto an existing id can install content whose `updatedAt` is older than the stamp. */
    fun clearStamp(itemId: String) {
        val config = read()
        if (itemId in config.stamps || itemId in config.cloudStamps) write(config.copy(stamps = config.stamps - itemId, cloudStamps = config.cloudStamps - itemId))
    }

    /** Forget every stamp in both maps: the rotation's call, while the index is still open. */
    fun clearAllStamps() {
        val config = read()
        if (config.stamps.isNotEmpty() || config.cloudStamps.isNotEmpty()) write(config.copy(stamps = emptyMap(), cloudStamps = emptyMap()))
    }

    private companion object { const val KEY = "backup" }
}
