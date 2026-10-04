package com.symmetricalpalmtree.soil.restore

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.symmetricalpalmtree.soil.backup.BackupPredicates
import com.symmetricalpalmtree.soil.data.SoilFiles
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Where a restore's fetched files wait before the commit installs them: `restore_staging/` on the
 * library's own volume, a sibling of the garden, so the commit is renames only. The layout
 * mirrors the live one. Every file streams to a `.part` and renames on completion. Nothing here
 * touches the live library.
 */
object RestoreStaging {

    const val DIR_NAME = "restore_staging"

    /** The slack the commit and the reopened index want after the staged copy has landed. */
    const val HEADROOM_BYTES = 64L shl 20

    fun dir(root: File): File = File(root, DIR_NAME)
    fun dir(context: Context): File = dir(SoilFiles.root(context))

    fun reset(root: File): File {
        val dir = dir(root)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, RestoreManifest.GARDEN).mkdirs()
        return dir
    }

    fun reset(context: Context): File = reset(SoilFiles.root(context))

    fun discard(root: File) { if (!dir(root).deleteRecursively()) Log.w(TAG, "staging discard was incomplete") }
    fun discard(context: Context) = discard(SoilFiles.root(context))

    fun stagedBytes(stagingDir: File): Long = stagingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Where [item] lands; a path that canonicalises outside staging is refused. */
    fun targetFor(stagingDir: File, item: Item): File {
        val target = File(stagingDir, item.relativePath)
        val root = stagingDir.canonicalFile.path
        val resolved = target.canonicalFile.path
        require(resolved.startsWith(root + File.separator)) { "staged path escapes the staging directory" }
        return target
    }

    fun writeStaged(target: File, expectedSize: Long, write: (OutputStream) -> Long): Boolean =
        stage(target, expectedSize) { part ->
            FileOutputStream(part).use { out ->
                val n = write(out)
                out.flush()
                out.fd.sync()
                n
            }
        }

    /** For a source that fills a file itself (the cloud's download into a descriptor). */
    suspend fun writeStagedVia(target: File, expectedSize: Long, fill: suspend (part: File) -> Long): Boolean =
        stage(target, expectedSize) { part -> fill(part) }

    private inline fun stage(target: File, expectedSize: Long, fill: (part: File) -> Long): Boolean {
        val part = File(target.path + BackupPredicates.PART_SUFFIX)
        try {
            target.parentFile?.mkdirs()
            if (part.exists()) part.delete()
            val written = fill(part)
            val landed = part.length()
            if (written < 0L) { part.delete(); return false }
            if (expectedSize >= 0L && (written != expectedSize || landed != expectedSize)) {
                Log.w(TAG, "short staged write ($written written, $landed landed, $expectedSize expected)")
                part.delete()
                return false
            }
            if (target.exists() && !target.delete()) { part.delete(); return false }
            if (!part.renameTo(target)) { part.delete(); return false }
            return true
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "staged write failed", e)
            part.delete()
            return false
        }
    }

    /** Pure: an unknown size on either side never fits. */
    fun fits(totalBytes: Long, usableBytes: Long, headroom: Long = HEADROOM_BYTES): Boolean =
        totalBytes >= 0L && usableBytes >= 0L && usableBytes - headroom >= totalBytes

    /** Free bytes on the library volume, or -1 for unknown. */
    fun usableBytes(root: File): Long = try { StatFs(root.path).availableBytes } catch (e: Exception) { -1L }
    fun usableBytes(context: Context): Long = usableBytes(SoilFiles.root(context))

    private const val TAG = "RestoreStaging"
}
