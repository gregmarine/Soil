package com.symmetricalpalmtree.soil.cloud

import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.CloudEntry
import kotlin.math.floor

/**
 * Everything the cloud browser decides that is not a view, pure so it is tested on the JVM. The
 * crumb is headed by the provider's own name (the person is looking at their cloud) and carries
 * no path, id or URL. The list paginates, never scrolls; *New folder…* is a row of it. Names
 * match exactly, because an upload replaces by name and resolves the same way. Browsing creates
 * nothing: `ensureFolder` is reached only from the New-folder row.
 */
object CloudBrowserRules {

    /** The row's min height and its 1 dp line, from `item_cloud_entry.xml`. */
    const val ROW_HEIGHT_DP = 68f
    const val ROW_SEPARATOR_DP = 1f

    sealed class Row {
        object NewFolder : Row()
        class Entry(val entry: CloudEntry) : Row()
    }

    enum class NewFolderOutcome { ENTER_EXISTING, CREATE, REFUSED }

    fun crumb(providerName: String, path: List<String>, separator: String): String = (listOf(providerName) + path).joinToString(separator)

    fun rows(entries: List<CloudEntry>, offersNewFolder: Boolean): List<Row> {
        val out = ArrayList<Row>(entries.size + 1)
        if (offersNewFolder) out += Row.NewFolder
        for (e in entries) out += Row.Entry(e)
        return out
    }

    fun pageCount(rowCount: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return maxOf(1, (rowCount + perPage - 1) / perPage)
    }

    fun page(rows: List<Row>, page: Int, perPage: Int): List<Row> {
        if (perPage <= 0) return emptyList()
        val start = page * perPage
        if (start < 0 || start >= rows.size) return emptyList()
        return rows.subList(start, minOf(start + perPage, rows.size))
    }

    fun itemsPerPage(bodyHeightPx: Int, density: Float): Int {
        val rowPx = (ROW_HEIGHT_DP + ROW_SEPARATOR_DP) * density
        if (rowPx <= 0f) return 1
        return maxOf(1, floor(bodyHeightPx / rowPx).toInt())
    }

    /** Up never climbs above the folder the browser opened on; Cancel is the way out of that one. */
    fun canGoUp(depth: Int, baseDepth: Int): Boolean = depth > baseDepth

    /** A file row answers a tap only where a file is what is being picked; otherwise it is drawn as information, never greyed. */
    fun fileTappable(picksFiles: Boolean): Boolean = picksFiles

    fun folderNamed(entries: List<CloudEntry>, name: String): CloudEntry? = entries.firstOrNull { it.isFolder && it.name == name }

    fun fileNamed(entries: List<CloudEntry>, name: String): CloudEntry? = entries.firstOrNull { !it.isFolder && it.name == name }

    /** A same-named folder is entered, not made twice; a name the seam cannot carry, or a depth past the cap, is refused before any call. */
    fun newFolderOutcome(name: String, entries: List<CloudEntry>, depth: Int): NewFolderOutcome = when {
        !CloudContract.isName(name) -> NewFolderOutcome.REFUSED
        depth + 1 > CloudContract.MAX_PATH_DEPTH -> NewFolderOutcome.REFUSED
        folderNamed(entries, name) != null -> NewFolderOutcome.ENTER_EXISTING
        else -> NewFolderOutcome.CREATE
    }
}
