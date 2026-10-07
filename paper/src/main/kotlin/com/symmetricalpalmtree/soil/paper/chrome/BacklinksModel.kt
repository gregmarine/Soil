package com.symmetricalpalmtree.soil.paper.chrome

import kotlin.math.roundToInt

/**
 * The arithmetic of a **backlinks panel** — "what links into this?" — shared by the Bible
 * reader's Notes and the calendar's Links (Calsprout, 2026-10-06). Pure, JVM-tested. The
 * grouping of the index's rows into places is each consumer's own (a passage's labels, a day's
 * labels); what is shared is the panel's shape: its width, its form by window width, its
 * pagination, and the first line of every row.
 */
object BacklinksModel {

    /** Below this window width the panel fills the screen. */
    const val SIDEBAR_MIN_DP = 480

    /** The panel's share of the window width: a row is a name, a page and a list of labels. */
    const val SIDEBAR_WIDTH_FRACTION = 0.60f

    fun fullScreen(windowWidthDp: Int): Boolean = windowWidthDp < SIDEBAR_MIN_DP

    fun sidebarWidthPx(windowWidthPx: Int): Int = (windowWidthPx * SIDEBAR_WIDTH_FRACTION).roundToInt()

    /** How many rows fit a body of [bodyHeightPx] when one row measures [rowHeightPx] — at least 1,
     *  and 1 for a nonsense row height. The row is measured, never a dp constant. */
    fun itemsPerPage(bodyHeightPx: Int, rowHeightPx: Int): Int {
        if (rowHeightPx <= 0) return 1
        return maxOf(1, bodyHeightPx / rowHeightPx)
    }

    /** At least one page, even of an empty list — the pager label always has something to say. */
    fun pageCount(size: Int, perPage: Int): Int =
        if (perPage <= 0) 1 else maxOf(1, (size + perPage - 1) / perPage)

    /** [page] brought inside `0 until pageCount`: the arrows are no-ops at either end, never disabled. */
    fun clampPage(page: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

    /** A row's first line: the item, then where in it: `"Study · Page 4"`, or `"Sermon · Document"`
     *  for an item with no pages. The words are the caller's, so this stays free of Android. */
    fun title(name: String, pageId: String, pageNumber: Int, pageWord: String, documentWord: String): String =
        if (pageId.isEmpty()) "$name · $documentWord" else "$name · $pageWord $pageNumber"

    /** One row of the panel: where it was written, and what it holds there. [key] is the consumer's handle. */
    data class Row<K>(val key: K, val title: String, val detail: String)
}
