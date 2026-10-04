package com.symmetricalpalmtree.soil.backup

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.crypto.KeyMaterial
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.io.File

/**
 * One file, complete in itself: the cloud leg's answer to the sidecar problem. The cloud has no
 * atomic swap, so two uploads can tear, and a fresh main file beside a stale WAL corrupts on
 * restore. Before every upload the file is copied into the cache with its WAL, the copy is opened
 * and checkpointed so the frames fold into it, and only a copy with no frames left that still
 * probes as encrypted is uploaded. Anything else answers null: refused this run, retried next.
 * The open runs only when a live WAL was copied; a sealed file needs no key to prove it whole.
 */
object SelfContainedSnapshot {

    private const val TAG = "CloudSnapshot"
    private const val DIR = "backup/cloud"

    fun dir(context: Context): File = File(context.cacheDir, DIR)

    fun clean(context: Context) { runCatching { dir(context).deleteRecursively() } }

    /** [fileId] is the raw-key cache id: the item id, [KeyMaterial.INDEX_FILE_ID], or a store's id. IO. */
    fun of(context: Context, live: File, destName: String, fileId: String): File? {
        if (!live.exists() || live.length() == 0L) return null
        val dir = dir(context)
        val snap = File(dir, destName)
        val snapWal = File(dir, destName + BackupPredicates.WAL_SUFFIX)
        try {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw java.io.IOException("could not create the cloud snapshot directory")
            live.copyTo(snap, overwrite = true)
            val liveWal = File(live.path + BackupPredicates.WAL_SUFFIX)
            val copiedWal = liveWal.exists() && liveWal.length() > 0L
            if (copiedWal) liveWal.copyTo(snapWal, overwrite = true)
            if (copiedWal) absorbWal(context, snap, fileId) else Slog.d(TAG) { "no live WAL; no open needed" }
        } catch (e: LockedFile) {
            Slog.d(TAG) { "snapshot refused: no key this process holds fits this file" }
            runCatching { dir.deleteRecursively() }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "snapshot of a ${live.length()}-byte file failed", e)
            runCatching { dir.deleteRecursively() }
            return null
        }
        val walLeft = snapWal.exists() && snapWal.length() > 0L
        val encrypted = SoilCrypto.probe(snap) == SoilFileKind.Encrypted
        if (walLeft || !encrypted || snap.length() == 0L) {
            Slog.d(TAG) { "snapshot refused: walLeft=$walLeft encrypted=$encrypted bytes=${snap.length()}" }
            runCatching { dir.deleteRecursively() }
            return null
        }
        Slog.d(TAG) { "snapshot ready: ${snap.length()} B, no sidecar" }
        return snap
    }

    private class LockedFile : Exception("no key this process holds fits this file")

    /** Open the copy, never the live file, and checkpoint its WAL into it. */
    private fun absorbWal(context: Context, snap: File, fileId: String) {
        val rawKey = KeyMaterial.peekVerified(context, fileId, snap)
        val db = if (rawKey != null) {
            SoilCrypto.openRawKey(snap, rawKey)
        } else {
            val passphrase = KeySession.get() ?: throw LockedFile()
            try {
                SoilCrypto.openRaw(snap, passphrase).also { db ->
                    val ok = runCatching { db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() } }.isSuccess
                    if (!ok) { runCatching { db.close() }; throw LockedFile() }
                }
            } catch (e: LockedFile) {
                throw e
            } catch (e: Exception) {
                throw LockedFile()
            }
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            runCatching { db.close() }
        }
    }
}
