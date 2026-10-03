package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract

/** What an export covers: the whole item, or one page of it (a pages exporter only). */
sealed class ExportScope {
    object Whole : ExportScope()
    data class Page(val pageId: String) : ExportScope()

    /** The page ids to render; empty means every page. */
    val pageIds: List<String> get() = when (this) { Whole -> emptyList(); is Page -> listOf(pageId) }

    companion object {
        fun seeded(pageId: String?): ExportScope = if (pageId.isNullOrEmpty()) Whole else Page(pageId)

        /** Whether an exporter of [sourceKind] is listed under [scope]: a file export has no page. */
        fun lists(sourceKind: Int, scope: ExportScope): Boolean = when (scope) {
            Whole -> true
            is Page -> sourceKind == ExportContract.SOURCE_PAGES
        }

        fun offerable(sourceKinds: Collection<Int>): Boolean = sourceKinds.any { lists(it, Page("")) }
    }
}
