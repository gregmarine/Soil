package com.symmetricalpalmtree.soil.files

import com.symmetricalpalmtree.soil.ext.CloudEntry
import java.io.File

/**
 * **The local browser's decision core**, pure: which names are shown, in what order, which files
 * a filter admits, and how a folder under the root is remembered. A path is the folder names
 * under the root, as the cloud browser's is under the provider's root.
 */
object LocalFiles {

    const val SEPARATOR = "/"

    /** Dotfiles and Android's own folders are noise on an e-ink list. */
    fun isShown(name: String): Boolean = name.isNotEmpty() && !name.startsWith(".") && name !in HIDDEN

    /** Folders first, then files, each by name without regard to case. */
    fun sorted(entries: List<CloudEntry>): List<CloudEntry> =
        entries.sortedWith(compareBy<CloudEntry> { !it.isFolder }.thenBy { it.name.lowercase() })

    /**
     * Whether a file is admitted by [mimes], judged by its extension: the any-type or nothing
     * admits every file; a family (image, any subtype) the family's extensions; an exact type
     * the extensions known for it. An extension nobody knows is admitted rather than hidden:
     * the caller judges the bytes.
     */
    fun matches(name: String, mimes: Array<String>?): Boolean {
        if (mimes.isNullOrEmpty() || mimes.any { it == ANY }) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        // No extension: nothing to judge by, so the file is shown.
        if (ext.isEmpty()) return true
        val known = EXTENSIONS[ext]
        if (known == null) return true
        return mimes.any { mime -> mime == known || (mime.endsWith("/*") && known.startsWith(mime.dropLast(1))) }
    }

    /** The stored form of a path under the root: its names joined by a slash, which no name can hold. */
    fun encodePath(path: List<String>): String = path.joinToString(SEPARATOR)

    /** The remembered path, or the root for anything that is not a plain list of names. */
    fun decodePath(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        val path = stored.split(SEPARATOR)
        if (path.any { it.isEmpty() || it == "." || it == ".." }) return emptyList()
        return path
    }

    /** The path of [dir] under [root], or null when it is not under it. */
    fun pathUnder(root: File, dir: File): List<String>? {
        val rootPath = root.absolutePath.trimEnd('/')
        val dirPath = dir.absolutePath.trimEnd('/')
        if (dirPath == rootPath) return emptyList()
        if (!dirPath.startsWith("$rootPath/")) return null
        return dirPath.removePrefix("$rootPath/").split('/').filter { it.isNotEmpty() }
    }

    /** The row's text: the whole path, `This device › Document › Exports`. */
    fun label(rootLabel: String, path: List<String>, separator: String): String = (listOf(rootLabel) + path).joinToString(separator)

    const val ANY = "*/*"

    private val HIDDEN = setOf("Android")

    private val EXTENSIONS: Map<String, String> = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "bmp" to "image/bmp",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown",
        "json" to "application/json", "zip" to "application/zip", "soil" to "application/octet-stream", "db" to "application/octet-stream",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "note" to "application/octet-stream", "mark" to "application/octet-stream", "epub" to "application/epub+zip", "cbz" to "application/vnd.comicbook+zip",
        "mp3" to "audio/mpeg", "mp4" to "video/mp4", "html" to "text/html", "csv" to "text/csv",
    )
}
