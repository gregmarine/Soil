package com.symmetricalpalmtree.soil.shell

/**
 * **Fixed pages, never a scroll.** A list longer than the screen is cut into pages of equal size
 * and turned with previous and next — scrolling smears on e-ink. Pure.
 */
object Paging {

    /** How many pages [count] things make at [perPage] each. An empty list is still one page. */
    fun pageCount(count: Int, perPage: Int): Int {
        require(perPage > 0) { "a page holds at least one" }
        return if (count <= 0) 1 else (count + perPage - 1) / perPage
    }

    /** [page] brought within what exists — a list that shrank must not leave the pager stranded. */
    fun clamp(page: Int, count: Int, perPage: Int): Int = page.coerceIn(0, pageCount(count, perPage) - 1)

    /** What page [page] holds; the last page may be short. */
    fun <T> slice(items: List<T>, page: Int, perPage: Int): List<T> {
        val p = clamp(page, items.size, perPage)
        return items.subList((p * perPage).coerceAtMost(items.size), ((p + 1) * perPage).coerceAtMost(items.size))
    }

    /** How many whole cells of [cell] fit in [space]; never fewer than one. */
    fun fit(space: Int, cell: Int): Int = if (cell <= 0) 1 else (space / cell).coerceAtLeast(1)
}
