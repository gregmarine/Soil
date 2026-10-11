package com.symmetricalpalmtree.soil.cloud

import android.content.Context
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.files.LocalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * **What the browser lists**: one tree of folders and files under a root, asked two things, the
 * entries under a path and a folder made under one. The cloud answers through the extension;
 * the device (Greg, 2026-10-10) through plain files under the shared storage's root. The
 * browser knows neither: a failure is an exception, the cloud's own kinds for the Connect offer
 * and the network sentence, anything else the plain *couldn't read* one.
 */
interface BrowserSource {
    /** What the crumb calls the root. */
    val label: String
    /** The sentence for a listing that failed for no named reason. */
    val failedTitleRes: Int
    val failedBodyRes: Int
    suspend fun list(path: List<String>): List<CloudEntry>
    suspend fun ensureFolder(path: List<String>)
}

class CloudSource(private val context: Context, val ref: Extension, override val label: String) : BrowserSource {
    override val failedTitleRes: Int get() = com.symmetricalpalmtree.soil.R.string.cloud_browser_failed_title
    override val failedBodyRes: Int get() = com.symmetricalpalmtree.soil.R.string.cloud_browser_failed_body
    override suspend fun list(path: List<String>): List<CloudEntry> = CloudClient.list(context, ref, path.toTypedArray())
    override suspend fun ensureFolder(path: List<String>) { CloudClient.ensureFolder(context, ref, path.toTypedArray()) }
}

/**
 * The device's files under [root]. An entry's id is a handle of this source's, never the path
 * (a path may hold a space, which an id may not): [fileFor] answers the file. In file mode
 * [mimes] narrows the files shown by extension; folders are always shown. A path that would
 * leave the root is refused as unreadable.
 */
class LocalSource(private val root: File, override val label: String, private val mimes: Array<String>? = null) : BrowserSource {
    override val failedTitleRes: Int get() = com.symmetricalpalmtree.soil.R.string.files_browser_failed_title
    override val failedBodyRes: Int get() = com.symmetricalpalmtree.soil.R.string.files_browser_failed_body

    private val handles = HashMap<String, File>()
    private var next = 0

    /** The file behind an entry this source listed, or null for a handle it never gave. */
    fun fileFor(id: String): File? = synchronized(handles) { handles[id] }

    private fun handle(file: File): String = synchronized(handles) { val id = "f${next++}"; handles[id] = file; id }

    fun resolve(path: List<String>): File {
        if (path.any { it.isEmpty() || it == "." || it == ".." || it.contains('/') }) throw IOException("a path segment is not a name")
        return path.fold(root) { dir, name -> File(dir, name) }
    }

    override suspend fun list(path: List<String>): List<CloudEntry> = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        val children = dir.listFiles() ?: throw IOException("the folder could not be listed")
        // A name the entry type refuses (edge whitespace, a control character) is left off the list, not a failure.
        val entries = children.asSequence()
            .filter { LocalFiles.isShown(it.name) && com.symmetricalpalmtree.soil.ext.CloudContract.isName(it.name) }
            .filter { it.isDirectory || LocalFiles.matches(it.name, mimes) }
            .map { CloudEntry(id = handle(it), name = it.name, isFolder = it.isDirectory, sizeBytes = if (it.isDirectory) 0L else it.length().coerceAtLeast(0L), modifiedAt = it.lastModified().coerceAtLeast(0L)) }
            .toList()
        LocalFiles.sorted(entries)
    }

    override suspend fun ensureFolder(path: List<String>) = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (dir.isDirectory) return@withContext
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("the folder could not be created")
    }
}
