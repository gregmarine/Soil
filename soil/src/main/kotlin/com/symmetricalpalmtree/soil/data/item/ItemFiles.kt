package com.symmetricalpalmtree.soil.data.item

import android.content.Context
import com.symmetricalpalmtree.soil.crypto.KeyOpener
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.OpenFiles
import com.symmetricalpalmtree.soil.crypto.SoilLockedException
import com.symmetricalpalmtree.soil.data.Schema
import com.symmetricalpalmtree.soil.data.SoilDb
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.index.LinkRows
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SeamSchema
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB

/** The file is not the item it was opened as. Nothing in it was changed. */
class ItemRefused(val verdict: ItemMeta.Verdict) : RuntimeException(verdict.name)

/**
 * **Every open and every close of an item file.** An item is one `.soil` file in the garden,
 * named by its id, under the global key.
 *
 * A file is made once, by [create], and never made by an open: a missing file is a missing item,
 * and is never replaced by an empty one.
 *
 * **Blocking.** IO only.
 */
object ItemFiles {

    private const val TAG = "ItemFiles"

    /** Make the file of a new item, at [schema], and close it. */
    fun create(context: Context, id: String, name: String, createdAt: Long, schema: SeamSchema) =
        create(context, id, name, createdAt, schema.kind) { file, passphrase -> SoilDb.create(file, passphrase, Schema(schema.kind, schema.steps)) }

    /**
     * Make the file of a new item of [kind] with **no** steps of the app's run: Soil's own tables
     * and nothing else, at version 0. The app that owns the kind brings it to its schema at its
     * first open and writes the first page. What Soil's own screens make.
     */
    fun createEmpty(context: Context, id: String, name: String, createdAt: Long, kind: String) =
        create(context, id, name, createdAt, kind) { file, passphrase -> SoilDb.createUnversioned(file, passphrase) }

    private fun create(context: Context, id: String, name: String, createdAt: Long, kind: String, open: (java.io.File, String) -> ZeticDB) {
        val app = context.applicationContext
        val passphrase = KeySession.get() ?: throw SoilLockedException("the library is locked")
        val file = SoilFiles.itemFile(app, id)
        val db = open(file, passphrase)
        try {
            db.execSQL(ItemMeta.CREATE)
            for ((key, value) in ItemMeta.rows(id, kind, name, createdAt)) {
                db.execSQL(ItemMeta.PUT, arrayOf<Any>(key, value))
            }
            ownTables(db)
            SoilDb.checkpoint(db)
        } finally {
            runCatching { db.close() }
        }
        // The file now has a salt: derive its key once, so every open after this is quick.
        KeyOpener.warm(app, id, file, passphrase)
        Slog.d(TAG) { "made an item of kind $kind" }
    }

    /**
     * Open the file of item [id] and bring it to [schema]. The file must say it is that item, of
     * that kind, before any step runs. Claims the file: the caller gives it back with [close].
     *
     * @throws ItemRefused when the file is another item's, another kind's, or a later Soil's
     * @throws IllegalStateException with [SeamLimits.SCHEMA_NEWER] when the file is newer than [schema]
     */
    fun open(context: Context, id: String, schema: SeamSchema): ZeticDB {
        val app = context.applicationContext
        val passphrase = KeySession.get() ?: throw SoilLockedException("the library is locked")
        val file = SoilFiles.itemFile(app, id)
        val key = KeyOpener.keyFor(app, id, file, passphrase)
        val db = try {
            SoilDb.open(file, key, Schema(schema.kind, schema.steps)) { opened ->
                val verdict = ItemMeta.verdict(readMeta(opened), id, schema.kind)
                if (verdict != ItemMeta.Verdict.OK) throw ItemRefused(verdict)
            }
        } catch (e: IllegalStateException) {
            // `Schema.pending` refuses a file newer than the schema. The file was not changed.
            if (e.message?.contains("newer than") == true) throw IllegalStateException(SeamLimits.SCHEMA_NEWER)
            throw e
        }
        // A file made before the mirror existed is given it here: Soil's tables are not steps of
        // the app's schema, and every one of them is made with IF NOT EXISTS.
        try {
            ownTables(db)
        } catch (t: Throwable) {
            runCatching { db.close() }
            throw t
        }
        OpenFiles.claim(file)
        return db
    }

    /** Soil's own tables beside the app's: the link mirror ([SeamLinks]), with its Bible columns
     *  and its calendar column added to a file made before them. `soil_meta` is made at [create] alone, since a file
     *  without it is not an item. */
    private fun ownTables(db: ZeticDB) {
        db.execSQL(SeamLinks.CREATE)
        db.execSQL(SeamLinks.CREATE_INDEX)
        if (!hasColumn(db, SeamLinks.TABLE, "bibleWire")) {
            db.execSQL(SeamLinks.ADD_BIBLE_WIRE)
            db.execSQL(SeamLinks.ADD_BIBLE_START)
            db.execSQL(SeamLinks.ADD_BIBLE_END)
        }
        db.execSQL(SeamLinks.CREATE_BIBLE_INDEX)
        if (!hasColumn(db, SeamLinks.TABLE, "calDate")) db.execSQL(SeamLinks.ADD_CAL_DATE)
        db.execSQL(SeamLinks.CREATE_CAL_INDEX)
    }

    private fun hasColumn(db: ZeticDB, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndex("name")
            while (c.moveToNext()) if (c.getString(name) == column) return true
            false
        }

    /**
     * The link mirror of item [id], read from its file without a session: what a rebuild of the
     * index reads for an item no app holds. The file is opened as it is, brought to no schema,
     * given its own tables, read and closed. Blocking; IO only.
     *
     * @throws ItemRefused when the file is another item's
     */
    fun readLinkMirror(context: Context, id: String, kind: String): List<com.symmetricalpalmtree.soil.data.index.LinkRow> {
        val app = context.applicationContext
        val passphrase = KeySession.get() ?: throw SoilLockedException("the library is locked")
        val file = SoilFiles.itemFile(app, id)
        val db = when (val key = KeyOpener.keyFor(app, id, file, passphrase)) {
            is com.symmetricalpalmtree.soil.data.FileKey.Passphrase -> com.symmetricalpalmtree.soil.crypto.SoilCrypto.openRaw(file, key.value)
            is com.symmetricalpalmtree.soil.data.FileKey.Raw -> com.symmetricalpalmtree.soil.crypto.SoilCrypto.openRawKey(file, key.value)
        }
        try {
            val verdict = ItemMeta.verdict(readMeta(db), id, kind)
            if (verdict != ItemMeta.Verdict.OK) throw ItemRefused(verdict)
            ownTables(db)
            return SqlCipherRowStore(db).query(com.symmetricalpalmtree.soil.paper.store.Statement(SeamLinks.READ)).rows.map { LinkRows.of(it) }
        } finally {
            runCatching { db.close() }
        }
    }

    /** Fold what is written into the file and close it. Never throws. */
    fun close(context: Context, id: String, db: ZeticDB) {
        SoilDb.checkpoint(db)
        runCatching { db.close() }.onFailure { Slog.d(TAG) { "close failed: ${it.javaClass.simpleName}" } }
        runCatching { OpenFiles.release(SoilFiles.itemFile(context.applicationContext, id)) }
    }

    /**
     * Purge what the app has soft-deleted and give the space back. `updatedAt` is not touched
     * anywhere: rows are removed, never rewritten. Never throws: a tidy that fails must never
     * cost a save.
     */
    fun tidy(db: ZeticDB, schema: SeamSchema) {
        if (schema.purge.isEmpty()) return
        val removed = try {
            var n = 0L
            db.beginTransaction()
            try {
                for (sql in schema.purge) {
                    val statement = db.compileStatement(sql)
                    try { n += statement.executeUpdateDelete() } finally { statement.close() }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            n
        } catch (e: Exception) {
            Slog.d(TAG) { "purge failed: ${e.javaClass.simpleName}" }
            return
        }
        if (removed == 0L) return
        // The full form, and only when something went: what a freed value leaves behind is not
        // given back by the incremental one.
        runCatching { db.execSQL("VACUUM") }.onFailure { Slog.d(TAG) { "VACUUM failed: ${it.javaClass.simpleName}" } }
        Slog.d(TAG) { "purged $removed row(s)" }
    }

    /** The file's name for itself is kept in step with the index. */
    fun writeName(db: ZeticDB, name: String) {
        db.execSQL(ItemMeta.PUT, arrayOf<Any>(ItemMeta.KEY_NAME, name))
    }

    private fun readMeta(db: ZeticDB): Map<String, String>? {
        val has = db.rawQuery(ItemMeta.EXISTS, NO_ARGS).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (!has) return null
        val found = HashMap<String, String>()
        db.rawQuery(ItemMeta.READ, NO_ARGS).use { c ->
            while (c.moveToNext()) found[c.getString(0)] = c.getString(1)
        }
        return found
    }

    /** Typed, so the call never lands on `rawQuery(String, Object...)`. */
    private val NO_ARGS: Array<String>? = null
}
