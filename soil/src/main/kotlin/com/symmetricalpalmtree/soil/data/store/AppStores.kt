package com.symmetricalpalmtree.soil.data.store

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.crypto.GlobalRotation
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

    private class Open(val db: ZeticDB, val rows: SqlCipherRowStore)

    private val open = HashMap<String, Open>()

    /** How many times [closeAll] has run in this process. A lease taken before a close holds a
     *  connection that is gone; it compares this and opens the store again (`AppStoreLease`). */
    private var closings = 0L

    /**
     * The store called [name], at [schema]. Throws [SoilLockedException] when the library is
     * locked, and whatever the open throws when the file will not open — it is left as it is.
     */
    @Synchronized
    fun open(context: Context, name: String, schema: Schema): RowStore = openRows(context, name, schema)

    /**
     * [open] for a lease: the store and the [closings] count it was opened under, read together,
     * so a close that lands between them cannot go unseen.
     */
    @Synchronized
    fun lend(context: Context, name: String, schema: Schema): Pair<SqlCipherRowStore, Long> =
        openRows(context, name, schema) to closings

    /** The [closings] count now. A lease whose count differs holds a closed connection. */
    @Synchronized
    fun closings(): Long = closings

    private fun openRows(context: Context, name: String, schema: Schema): SqlCipherRowStore {
        // A store this process already holds may be asked for at a newer schema than it was
        // opened with — a Sprout app updated while Soil ran (Calsprout's events step, 2026-10-05).
        // The steps it is missing run now; a current store costs one PRAGMA.
        open[name]?.let { SoilDb.migrate(it.db, schema); return it.rows }
        val app = context.applicationContext
        val passphrase = KeySession.get() ?: throw SoilLockedException("the library is locked")
        // While a rotation marker stands the library is in two keys: a store opened (or made)
        // now would be under the old one, or would hold a file the rotation is about to re-key.
        GlobalRotation.refuseWhileRotating(app)
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

    /** Fold the WAL of a store this process holds open, before its file is copied. A store never
     *  opened here is left alone: the copy carries its WAL alongside. Never throws. */
    @Synchronized
    fun checkpointIfOpen(name: String) {
        open[name]?.let { SoilDb.checkpoint(it.db) }
    }

    /** Checkpoint and close every store — before a rotation re-keys them, on Forget, before a
     *  restore. Every lease taken before this opens its store again at its next call. Never throws. */
    @Synchronized
    fun closeAll(context: Context) {
        val app = context.applicationContext
        for ((name, store) in open) {
            SoilDb.checkpoint(store.db)
            runCatching { store.db.close() }.onFailure { Log.w(TAG, "close failed for a store", it) }
            runCatching { OpenFiles.release(SoilFiles.storeFile(app, name)) }
        }
        open.clear()
        closings++
    }
}
