package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract

/** The file names an export suggests: the item's name made safe, the id when nothing is left of it. */
object ExportNaming {

    private val ILLEGAL = Regex("[^a-zA-Z0-9_\\-. ]")
    const val MAX_TITLE_CHARS = 80

    fun base(displayName: String, itemId: String): String {
        val cleaned = ILLEGAL.replace(displayName, "").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") itemId else cleaned
    }

    fun fileName(stem: String, fileExtension: String): String = "$stem.$fileExtension"

    /** `<item> - <title>`, else `<item> - page N`, else the item alone. */
    fun pageStem(displayName: String, itemId: String, pageNumber: Int, pageTitle: String?): String {
        val stem = base(displayName, itemId)
        val title = pageTitle?.let { ILLEGAL.replace(it, "").trim() }?.take(MAX_TITLE_CHARS)?.trim()
        return when {
            !title.isNullOrEmpty() && title != "." && title != ".." -> "$stem - $title"
            pageNumber >= 1 -> "$stem - page $pageNumber"
            else -> stem
        }
    }

    data class PageName(val number: Int = 0, val title: String? = null)

    /** The stem as the spec carries it, within the contract's cap. */
    fun specNameOf(stem: String): String = stem.take(ExportContract.MAX_NAME_CHARS)
}
