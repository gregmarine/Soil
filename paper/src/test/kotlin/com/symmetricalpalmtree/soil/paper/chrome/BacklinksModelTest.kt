package com.symmetricalpalmtree.soil.paper.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklinksModelTest {

    @Test
    fun `the panel fills a narrow window and is sixty per cent of a wide one`() {
        assertTrue(BacklinksModel.fullScreen(479))
        assertFalse(BacklinksModel.fullScreen(480))
        assertEquals(449, BacklinksModel.sidebarWidthPx(749))
    }

    @Test
    fun `pagination never has fewer than one page or one row, and clamps at the ends`() {
        assertEquals(1, BacklinksModel.itemsPerPage(1000, 0))
        assertEquals(3, BacklinksModel.itemsPerPage(1000, 300))
        assertEquals(1, BacklinksModel.pageCount(0, 5))
        assertEquals(2, BacklinksModel.pageCount(6, 5))
        assertEquals(1, BacklinksModel.pageCount(5, 0))
        assertEquals(0, BacklinksModel.clampPage(-1, 3))
        assertEquals(2, BacklinksModel.clampPage(9, 3))
        assertEquals(0, BacklinksModel.clampPage(4, 0))
    }

    @Test
    fun `the first line names the item and the page, or the document`() {
        assertEquals("Study · Page 4", BacklinksModel.title("Study", "p1", 4, "Page", "Document"))
        assertEquals("Sermon · Document", BacklinksModel.title("Sermon", "", 0, "Page", "Document"))
    }
}
