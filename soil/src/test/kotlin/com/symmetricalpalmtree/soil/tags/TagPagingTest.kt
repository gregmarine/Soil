package com.symmetricalpalmtree.soil.tags

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pager's arithmetic: the part that gets an off-by-one and the part a screenshot cannot check. */
class TagPagingTest {

    @Test
    fun `rows per page is whole rows against the real band, at least one`() {
        assertEquals(5, TagPaging.rowsPerPage(500, 100))
        assertEquals(5, TagPaging.rowsPerPage(599, 100))
        assertEquals(1, TagPaging.rowsPerPage(40, 100))
        assertEquals(1, TagPaging.rowsPerPage(0, 100))
        assertEquals(1, TagPaging.rowsPerPage(500, 0))
    }

    @Test
    fun `page count is at least one and the clamp keeps the page inside`() {
        assertEquals(1, TagPaging.pageCount(0, 5))
        assertEquals(1, TagPaging.pageCount(5, 5))
        assertEquals(2, TagPaging.pageCount(6, 5))
        assertEquals(0, TagPaging.clampPage(-3, 12, 5))
        assertEquals(2, TagPaging.clampPage(9, 12, 5))
        assertEquals(1, TagPaging.clampPage(2, 7, 5))
    }

    @Test
    fun `slice is the page and never throws`() {
        val items = (1..12).toList()
        assertEquals(listOf(1, 2, 3, 4, 5), TagPaging.slice(items, 0, 5))
        assertEquals(listOf(11, 12), TagPaging.slice(items, 2, 5))
        assertEquals(emptyList<Int>(), TagPaging.slice(items, 9, 5))
        assertEquals(emptyList<Int>(), TagPaging.slice(items, -1, 5))
        assertEquals(emptyList<Int>(), TagPaging.slice(items, 0, 0))
    }
}
