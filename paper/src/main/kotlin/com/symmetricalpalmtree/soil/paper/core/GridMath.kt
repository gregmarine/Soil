package com.symmetricalpalmtree.soil.paper.core

/**
 * A card grid is **paginated, never scrolling**: a page holds exactly the cards that fit the real
 * container, and the rest live on the next page. Pure arithmetic; the views measure the band and
 * hand the numbers over. A card is at least [minCardWidthPx] wide and [CARD_ASPECT] tall for its
 * width; both counts floor at 1, so a container smaller than one card still shows one.
 */
object GridMath {

    /** 1 : 1.4, a page standing up. */
    const val CARD_ASPECT = 1.4f

    fun columns(containerWidthPx: Int, minCardWidthPx: Int): Int {
        if (containerWidthPx <= 0 || minCardWidthPx <= 0) return 1
        return (containerWidthPx / minCardWidthPx).coerceAtLeast(1)
    }

    fun rows(containerWidthPx: Int, containerHeightPx: Int, minCardWidthPx: Int, aspect: Float = CARD_ASPECT): Int {
        if (containerHeightPx <= 0) return 1
        val cardHeight = (cardWidthPx(containerWidthPx, minCardWidthPx) * aspect).toInt()
        if (cardHeight <= 0) return 1
        return (containerHeightPx / cardHeight).coerceAtLeast(1)
    }

    fun cardWidthPx(containerWidthPx: Int, minCardWidthPx: Int): Int =
        (containerWidthPx / columns(containerWidthPx, minCardWidthPx)).coerceAtLeast(1)

    fun cardsPerPage(containerWidthPx: Int, containerHeightPx: Int, minCardWidthPx: Int, aspect: Float = CARD_ASPECT): Int =
        columns(containerWidthPx, minCardWidthPx) * rows(containerWidthPx, containerHeightPx, minCardWidthPx, aspect)

    /** Never zero: an empty listing is one empty page, so the pager always reads "1 / 1". */
    fun pageCount(totalItems: Int, cardsPerPage: Int): Int {
        if (totalItems <= 0 || cardsPerPage <= 0) return 1
        return (totalItems - 1) / cardsPerPage + 1
    }

    fun clampPage(pageIndex: Int, pageCount: Int): Int = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    /** The half-open index range of the [pageIndex] slice; empty past the end. */
    fun pageRange(pageIndex: Int, cardsPerPage: Int, totalItems: Int): IntRange {
        val start = pageIndex * cardsPerPage
        if (start >= totalItems || cardsPerPage <= 0) return IntRange.EMPTY
        return start until minOf(start + cardsPerPage, totalItems)
    }
}
