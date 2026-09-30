package com.symmetricalpalmtree.soil.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PagingTest {

    @Test
    fun pagesAreCountedUp() {
        assertEquals(1, Paging.pageCount(0, 12))
        assertEquals(1, Paging.pageCount(1, 12))
        assertEquals(1, Paging.pageCount(12, 12))
        assertEquals(2, Paging.pageCount(13, 12))
        assertEquals(3, Paging.pageCount(36, 12))
    }

    @Test
    fun aPageHoldsAtLeastOne() {
        assertThrows(IllegalArgumentException::class.java) { Paging.pageCount(5, 0) }
    }

    @Test
    fun theLastPageMayBeShort() {
        val items = (1..14).toList()
        assertEquals((1..12).toList(), Paging.slice(items, 0, 12))
        assertEquals(listOf(13, 14), Paging.slice(items, 1, 12))
    }

    @Test
    fun aPageThatNoLongerExistsBecomesTheLast() {
        val items = (1..14).toList()
        assertEquals(1, Paging.clamp(7, items.size, 12))
        assertEquals(0, Paging.clamp(-3, items.size, 12))
        assertEquals(listOf(13, 14), Paging.slice(items, 7, 12))
    }

    @Test
    fun anEmptyListIsOneEmptyPage() {
        assertEquals(emptyList<Int>(), Paging.slice(emptyList<Int>(), 0, 12))
        assertEquals(0, Paging.clamp(3, 0, 12))
    }

    @Test
    fun wholeCellsOnly_andNeverNone() {
        assertEquals(4, Paging.fit(748, 180))
        assertEquals(1, Paging.fit(100, 180))
        assertEquals(1, Paging.fit(0, 180))
        assertEquals(1, Paging.fit(500, 0))
    }
}
