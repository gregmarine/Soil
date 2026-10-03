package com.symmetricalpalmtree.soil.importing

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.crypto.KeyMaterial
import com.symmetricalpalmtree.soil.crypto.OpenFiles
import com.symmetricalpalmtree.soil.crypto.Sidecars
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.item.ItemMeta
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.export.ExportStamp
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SeamSql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * **The file side of an import**: the cache it lands in, what the file says of itself, a new
 * id when it needs one, and the move into the garden. Every write is to the cache copy until
 * the one atomic rename at the end.
 */
object ItemImport {

    private const val TAG = "ItemImport"
    private const val DIR = "import"
    private const val INCOMING = "incoming.soil"

    enum class Problem { DELIVERY, SHORT, NOT_AN_ITEM, NO_KEY, KEYING, UNREADABLE, IN_USE, WRITE, NO_APP, NEWER }

    class ImportProblem(val problem: Problem, cause: Throwable? = null) : Exception("import problem: $problem", cause)

    fun prepareCache(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) { Log.w(TAG, "could not create the import cache directory"); throw ImportProblem(Problem.WRITE) }
        return File(dir, INCOMING)
    }

    fun clean(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }

    /** What the file's own meta table says: its id as written and as usable, its kind, its name,
     *  and the folders it was exported from. */
    class Manifest(val rawId: String?, val fileId: String?, val kind: String, val name: String?, val createdAt: Long?, val folderPath: List<ExportStamp.Folder>)

    suspend fun readManifest(file: File, passphrase: String): Manifest = withContext(Dispatchers.IO) {
        val db = try {
            SoilCrypto.openRaw(file, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "manifest open failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.UNREADABLE, e)
        }
        try {
            if (queryLong(db, ItemMeta.EXISTS) == 0L) { Log.w(TAG, "no ${ItemMeta.TABLE} table: not an item"); throw ImportProblem(Problem.NOT_AN_ITEM) }
            val meta = HashMap<String, String>()
            db.rawQuery(ItemMeta.READ, null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
            val format = meta[ItemMeta.KEY_FORMAT]?.toIntOrNull() ?: throw ImportProblem(Problem.NOT_AN_ITEM)
            if (format > ItemMeta.FORMAT.toInt()) throw ImportProblem(Problem.NEWER)
            val kind = meta[ItemMeta.KEY_KIND]?.takeIf { it.isNotBlank() } ?: throw ImportProblem(Problem.NOT_AN_ITEM)
            val rawId = meta[ItemMeta.KEY_ID]?.takeIf { it.isNotBlank() }
            Slog.d(TAG) { "manifest: kind $kind, ${if (rawId == null) "no id" else "an id"}" }
            Manifest(rawId, SafeImportId.orNull(rawId), kind, meta[ItemMeta.KEY_NAME], meta[ItemMeta.KEY_CREATED_AT]?.toLongOrNull(), ExportStamp.decodePath(meta[ExportStamp.KEY_FOLDER_PATH]))
        } catch (e: ImportProblem) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "manifest read failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.UNREADABLE, e)
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * Give the cache file another id: Soil's meta and link mirror by Soil's own hand, the app's
     * rows by the statements the app's renderer gave, each held to the seam's statement rules.
     * One transaction, then a checkpoint so the main file holds it all.
     */
    suspend fun relabel(file: File, passphrase: String, oldId: String, newId: String, appStatements: List<String>) = withContext(Dispatchers.IO) {
        for (sql in appStatements) SeamSql.checkExec(sql)
        val db = try {
            SoilCrypto.openRaw(file, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "relabel open failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.UNREADABLE, e)
        }
        try {
            db.beginTransaction()
            try {
                db.execSQL(ItemMeta.PUT, arrayOf(ItemMeta.KEY_ID, newId))
                if (queryLong(db, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '${SeamLinks.TABLE}'") > 0L) {
                    db.execSQL("UPDATE ${SeamLinks.TABLE} SET targetItemId = ? WHERE targetItemId = ?", arrayOf(newId, oldId))
                }
                for (sql in appStatements) db.execSQL(sql)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "relabel failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.UNREADABLE, e)
        } finally {
            runCatching { db.close() }
        }
    }

    /** The keyed file into the garden under [itemId]: staged beside the target, verified, renamed over it. */
    suspend fun placeInGarden(context: Context, keyed: File, itemId: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val target = SoilFiles.itemFile(app, itemId)
        if (ItemSessions.isHeld(itemId) || OpenFiles.isOpen(target)) { Log.w(TAG, "refusing to write an item that is open"); throw ImportProblem(Problem.IN_USE) }
        val wal = File(keyed.path + "-wal")
        if (wal.exists() && wal.length() > 0L) { Log.w(TAG, "incoming WAL not checkpointed; refusing"); throw ImportProblem(Problem.WRITE) }
        val bytes = keyed.length()
        if (bytes == 0L) throw ImportProblem(Problem.WRITE)
        val staging = File(target.parentFile, "${target.name}.import.tmp")
        try {
            target.parentFile?.mkdirs()
            runCatching { staging.delete() }
            keyed.copyTo(staging, overwrite = true)
            if (staging.length() != bytes) { Log.w(TAG, "staged ${staging.length()} of $bytes bytes"); throw ImportProblem(Problem.WRITE) }
            if (!staging.renameTo(target)) { Log.w(TAG, "staging rename failed"); throw ImportProblem(Problem.WRITE) }
            Sidecars.of(target).forEach { runCatching { it.delete() } }
            if (target.length() != bytes) { Log.w(TAG, "wrote ${target.length()} of $bytes bytes"); throw ImportProblem(Problem.WRITE) }
        } catch (e: ImportProblem) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "garden write failed: ${e.javaClass.simpleName}")
            throw ImportProblem(Problem.WRITE, e)
        } finally {
            runCatching { if (staging.exists()) staging.delete() }
        }
        KeyMaterial.invalidate(app, itemId)
        Slog.d(TAG) { "placed $bytes bytes in the garden" }
    }

    private fun queryLong(db: ZeticDB, sql: String): Long = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
}
