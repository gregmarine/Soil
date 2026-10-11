package com.symmetricalpalmtree.soil.backup

import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * **The local destination's door**, one shape for two floors: the persisted SAF tree
 * ([SafBackupWriter]) and, since 2026-10-10, a folder of the shared storage by path
 * ([FileBackupWriter]) once All files access is on. The engine speaks Uris either way; a path
 * rides as a `file:` Uri. Every write is atomic by the same protocol: `<name>.part` streamed and
 * verified, the previous copy to `<name>.old`, the part renamed in, the `.old` dropped; a `.old`
 * standing alone is the last good copy a crash stranded and is renamed back.
 */
interface BackupWriter {
    data class Entry(val uri: Uri, val name: String, val size: Long, val isDir: Boolean)

    /** The root as a Uri, or null when it no longer resolves. */
    fun root(): Uri?
    /** The children of [dirUri], or null when the listing itself failed; never "empty" for a failure. */
    fun list(dirUri: Uri): List<Entry>?
    fun find(dirUri: Uri, name: String): Entry? = list(dirUri)?.firstOrNull { it.name == name }
    /** Find or create the [name] subdirectory of [dirUri]. */
    fun ensureDir(dirUri: Uri, name: String): Uri?
    fun delete(uri: Uri): Boolean
    /** Write [source] into [dirUri] as [name], atomically. False leaves the previous copy in place. */
    fun writeAtomic(dirUri: Uri, name: String, source: File): Boolean
}

/** The source's twin: a tree read one level deep for a restore. */
interface BackupReader {
    data class Entry(val uri: Uri, val name: String, val size: Long, val isDir: Boolean, val lastModified: Long)

    fun root(): Uri?
    fun rootName(): String?
    fun list(dirUri: Uri): List<Entry>?
    fun open(uri: Uri): InputStream?
}

/** A folder of the shared storage by path. Nothing here throws; every failure logs and answers false or null. Paths are never logged. */
class FileBackupWriter(private val dir: File) : BackupWriter {

    override fun root(): Uri? = if (dir.isDirectory || dir.mkdirs()) Uri.fromFile(dir) else null.also { Log.w(TAG, "destination folder did not resolve") }

    override fun list(dirUri: Uri): List<BackupWriter.Entry>? {
        val folder = fileOf(dirUri) ?: return null
        val children = folder.listFiles() ?: return null.also { Log.w(TAG, "destination listing failed") }
        return children.map { BackupWriter.Entry(Uri.fromFile(it), it.name, if (it.isDirectory) -1L else it.length(), it.isDirectory) }
    }

    override fun ensureDir(dirUri: Uri, name: String): Uri? {
        val folder = fileOf(dirUri) ?: return null
        val sub = File(folder, name)
        if (sub.isDirectory) return Uri.fromFile(sub)
        if (sub.exists()) return null
        return if (sub.mkdirs()) Uri.fromFile(sub) else null.also { Log.w(TAG, "could not create subdirectory") }
    }

    override fun delete(uri: Uri): Boolean {
        val f = fileOf(uri) ?: return false
        return f.delete().also { if (!it) Log.w(TAG, "delete failed") }
    }

    override fun writeAtomic(dirUri: Uri, name: String, source: File): Boolean {
        val folder = fileOf(dirUri) ?: return false
        val part = File(folder, name + BackupPredicates.PART_SUFFIX)
        val old = File(folder, name + BackupPredicates.OLD_SUFFIX)
        val target = File(folder, name)
        try {
            if (part.exists() && !part.delete()) return false
            if (old.exists()) {
                if (!target.exists()) {
                    // A crash inside a previous swap: the `.old` is the only good copy.
                    if (!old.renameTo(target)) return false
                } else if (!old.delete()) return false
            }
            var landed = -1L
            part.outputStream().use { out -> source.inputStream().use { inp -> landed = inp.copyTo(out) }; out.fd.sync() }
            val expected = source.length()
            if (landed != expected || part.length() != expected) {
                Log.w(TAG, "short write ($landed streamed, ${part.length()} landed, $expected expected)")
                part.delete()
                return false
            }
            val hadExisting = target.exists()
            if (hadExisting && !target.renameTo(old)) { part.delete(); return false }
            if (!part.renameTo(target)) {
                if (hadExisting) old.renameTo(target)
                return false
            }
            if (hadExisting) old.delete()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $name", e)
            runCatching { part.delete() }
            return false
        }
    }

    private fun fileOf(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val f = File(uri.path ?: return null)
        // Only the chosen folder and what is under it.
        val rootPath = dir.absolutePath.trimEnd('/')
        return if (f.absolutePath == rootPath || f.absolutePath.startsWith("$rootPath/")) f else null
    }

    private companion object { const val TAG = "FileBackupWriter" }
}

/** A folder of the shared storage by path, read for a restore. */
class FileBackupReader(private val dir: File) : BackupReader {

    override fun root(): Uri? = if (dir.isDirectory) Uri.fromFile(dir) else null.also { Log.w(TAG, "source folder did not resolve") }

    override fun rootName(): String? = dir.name.takeIf { it.isNotEmpty() }

    override fun list(dirUri: Uri): List<BackupReader.Entry>? {
        if (dirUri.scheme != "file") return null
        val folder = File(dirUri.path ?: return null)
        val children = folder.listFiles() ?: return null.also { Log.w(TAG, "source listing failed") }
        return children.map { BackupReader.Entry(Uri.fromFile(it), it.name, if (it.isDirectory) -1L else it.length(), it.isDirectory, it.lastModified()) }
    }

    override fun open(uri: Uri): InputStream? = try {
        if (uri.scheme == "file") File(checkNotNull(uri.path)).inputStream() else null
    } catch (e: Exception) {
        Log.w(TAG, "source file did not open", e)
        null
    }

    private companion object { const val TAG = "FileBackupReader" }
}
