package com.symmetricalpalmtree.soil.data.store

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.crypto.KeyOpener
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.OpenFiles
import com.symmetricalpalmtree.soil.crypto.RotationPlan
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.crypto.SoilLockedException
import com.symmetricalpalmtree.soil.data.FileKey
import com.symmetricalpalmtree.soil.data.Schema
import com.symmetricalpalmtree.soil.data.SoilDb
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.paper.store.RowStore
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB

/**
 * **The app stores**: one encrypted database per app that keeps its data in Soil rather than in
 * item files, under the global key only. Soil's own Scratch Pad is the first.
 *
 * One connection per store, opened on first use and kept for the process. A store is created the
 * first time it is asked for — the person never makes one — and only then: an existing file that
 * will not open is never created over.
 *
 * **Blocking.** IO only.
 */
object AppStores {

    private const val TAG = "AppStores"

    private class Open(val db: ZeticDB, val rows: RowStore)

    private val open = HashMap<String, Open>()

    /**
     * The store called [name], at [schema]. Throws [SoilLockedException] when the library is
     * locked, and whatever the open throws when the file will not open — it is left as it is.
     */
    @Synchronized
    fun open(context: Context, name: String, schema: Schema): RowStore {
        open[name]?.let { return it.rows }
        val app = context.applicationContext
        val passphrase = KeySession.get() ?: throw SoilLockedException("the library is locked")
        val file = SoilFiles.storeFile(app, name)
        val id = RotationPlan.storeId(name)
        val db = if (SoilCrypto.probe(file) == SoilFileKind.Invalid && !(file.exists() && file.length() > 0L)) {
            // Genuinely absent (or zero bytes): mint it. A non-empty file that reads as invalid
            // is damaged, and falls through to the open, which refuses it.
            SoilDb.create(file, passphrase, schema).also { KeyOpener.warm(app, id, file, passphrase) }
        } else {
            val key: FileKey = KeyOpener.keyFor(app, id, file, passphrase)
            SoilDb.open(file, key, schema)
        }
        OpenFiles.claim(file)
        return SqlCipherRowStore(db).also { open[name] = Open(db, it) }
    }

    @Synchronized
    fun isOpen(name: String): Boolean = name in open

    /** Checkpoint and close every store — before a rotation re-keys them. Never throws. */
    @Synchronized
    fun closeAll(context: Context) {
        val app = context.applicationContext
        for ((name, store) in open) {
            SoilDb.checkpoint(store.db)
            runCatching { store.db.close() }.onFailure { Log.w(TAG, "close failed for a store", it) }
            runCatching { OpenFiles.release(SoilFiles.storeFile(app, name)) }
        }
        open.clear()
    }
}
