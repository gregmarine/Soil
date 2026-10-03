package com.symmetricalpalmtree.soil.tags

/**
 * The tag list's pager as arithmetic. Rows are measured against the real band, never a guessed
 * count; the arrows never disable; with one page the pager is `INVISIBLE`, not `GONE`, so the
 * band keeps its height.
 */
object TagPaging {

    /** Whole rows of [rowPx] in [bandPx]; at least one. */
    fun rowsPerPage(bandPx: Int, rowPx: Int): Int {
        if (rowPx <= 0) return 1
        return (bandPx / rowPx).coerceAtLeast(1)
    }

    fun pageCount(total: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return ((total + perPage - 1) / perPage).coerceAtLeast(1)
    }

    fun clampPage(page: Int, total: Int, perPage: Int): Int = page.coerceIn(0, pageCount(total, perPage) - 1)

    fun <T> slice(items: List<T>, page: Int, perPage: Int): List<T> {
        if (perPage <= 0) return emptyList()
        val from = page * perPage
        if (from >= items.size || from < 0) return emptyList()
        return items.subList(from, minOf(from + perPage, items.size))
    }
}
