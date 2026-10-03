package com.symmetricalpalmtree.soil.export

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.data.SoilDb
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * **The item file, ready to leave**: stamped with what the library knows of it, checkpointed so
 * the main file holds everything, and copied whole into the export cache. The copy is what an
 * exporter streams, or what a keying transform starts from. An item held open by an app is
 * refused: its file may be mid-write.
 */
object ExportArtifact {

    private const val TAG = "ExportArtifact"
    internal const val DIR = "export"

    enum class Problem { IN_USE, LOCKED, MISSING, UNREADABLE, COPY_FAILED }

    sealed class Outcome {
        class Ready(val file: File, val bytes: Long) : Outcome()
        class Failed(val problem: Problem) : Outcome()
    }

    suspend fun prepare(context: Context, itemId: String, stamp: ExportStamp.Stamp?): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val source = SoilFiles.itemFile(app, itemId)
        if (!source.exists() || source.length() == 0L) return@withContext Outcome.Failed(Problem.MISSING)
        if (ItemSessions.isHeld(itemId)) {
            Log.w(TAG, "refusing to export an item an app holds open")
            return@withContext Outcome.Failed(Problem.IN_USE)
        }
        val passphrase = KeySession.get() ?: return@withContext Outcome.Failed(Problem.LOCKED)
        try {
            val db = SoilCrypto.openRaw(source, passphrase)
            try {
                if (stamp != null) runCatching { ExportStamp.write(db, stamp) }.onFailure { Log.w(TAG, "export stamp skipped: ${it.javaClass.simpleName}") }
                SoilDb.checkpoint(db)
            } finally {
                runCatching { db.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "export open failed: ${e.javaClass.simpleName}")
            return@withContext Outcome.Failed(Problem.UNREADABLE)
        }
        val wal = File(source.path + "-wal")
        if (wal.exists() && wal.length() > 0L) {
            Log.w(TAG, "WAL not checkpointed (${wal.length()} bytes); refusing a stale copy")
            return@withContext Outcome.Failed(Problem.COPY_FAILED)
        }
        val artifact = try {
            val out = File(freshDir(app), "$itemId.soil")
            source.copyTo(out, overwrite = true)
            out
        } catch (e: Exception) {
            Log.w(TAG, "export cache copy failed: ${e.javaClass.simpleName}")
            return@withContext Outcome.Failed(Problem.COPY_FAILED)
        }
        if (artifact.length() != source.length() || artifact.length() == 0L) {
            Log.w(TAG, "export copy is ${artifact.length()} of ${source.length()} bytes")
            return@withContext Outcome.Failed(Problem.COPY_FAILED)
        }
        Slog.d(TAG) { "prepared ${artifact.length()} bytes for export" }
        Outcome.Ready(artifact, artifact.length())
    }

    fun clean(context: Context) {
        runCatching { File(context.cacheDir, DIR).deleteRecursively() }
    }

    internal fun freshDir(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("could not create the export cache directory")
        return dir
    }
}
