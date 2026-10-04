package com.symmetricalpalmtree.soil.templates

import com.symmetricalpalmtree.soil.data.index.TemplateRow
import com.symmetricalpalmtree.soil.paper.templates.TemplateIds
import com.symmetricalpalmtree.soil.paper.templates.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateLibraryTest {

    private fun row(id: String, name: String, folder: Boolean = false, updated: Long = 0L) =
        TemplateRow(id, "", name, folder, 0, 0L, updated, if (folder) 0L else 10L)

    private val labels = listOf("Lined", "Dotted", "Grid")

    @Test
    fun `the root leads with Blank and Default, then the rows`() {
        val cards = TemplateLibrary.rootCards("Blank", "Default", listOf(row("f", "Work", folder = true), row("t", "Ruled")))
        assertEquals(listOf(TemplateIds.BLANK, TemplateIds.DEFAULT_FOLDER, "f", "t"), cards.map { it.id })
        assertTrue(cards[2] is TemplateCard.Folder)
        assertTrue(cards[3] is TemplateCard.Static)
    }

    @Test
    fun `Default holds the three built-ins in one order, forever`() {
        val cards = TemplateLibrary.defaultCards(labels)
        assertEquals(listOf(TemplateKind.LINED, TemplateKind.DOTTED, TemplateKind.GRID), cards.map { (it as TemplateCard.BuiltIn).kind })
        assertEquals(listOf("Lined", "Dotted", "Grid"), cards.map { it.name })
        assertTrue(cards.all { it.isSentinel })
    }

    @Test
    fun `folders come first, names compare without case, and the order applies within each group`() {
        val rows = listOf(row("b", "bravo", updated = 2), row("F", "Zed", folder = true), row("a", "Alpha", updated = 1), row("c", "Charlie", updated = 3))
        assertEquals(listOf("F", "a", "b", "c"), TemplateLibrary.sorted(rows, SortField.NAME, SortOrder.ASC).map { it.id })
        assertEquals(listOf("F", "c", "b", "a"), TemplateLibrary.sorted(rows, SortField.NAME, SortOrder.DESC).map { it.id })
        assertEquals(listOf("F", "c", "b", "a"), TemplateLibrary.sorted(rows, SortField.MODIFIED, SortOrder.DESC).map { it.id })
    }

    @Test
    fun `the built-ins are pinnable, Blank and Default are not, a row is`() {
        assertTrue(TemplateLibrary.isPinnable(TemplateIds.GRID))
        assertFalse(TemplateLibrary.isPinnable(TemplateIds.BLANK))
        assertFalse(TemplateLibrary.isPinnable(TemplateIds.DEFAULT_FOLDER))
        assertTrue(TemplateLibrary.isPinnable("row"))
    }

    @Test
    fun `the pinned shelf leads with the pinned built-ins in their fixed order`() {
        val cards = TemplateLibrary.pinnedCards(setOf(TemplateIds.GRID, TemplateIds.LINED, "t"), listOf(row("t", "Ruled")), labels)
        assertEquals(listOf(TemplateIds.LINED, TemplateIds.GRID, "t"), cards.map { it.id })
    }

    @Test
    fun `the recents shelf keeps stored order, dedupes, keeps a sentinel and drops a dead row`() {
        val alive = mapOf("t" to row("t", "Ruled"))
        val cards = TemplateLibrary.recentCards(listOf("t", TemplateIds.DOTTED, "gone", "t"), alive, labels)
        assertEquals(listOf("t", TemplateIds.DOTTED), cards.map { it.id })
        assertEquals(setOf("t") + TemplateLibrary.PINNABLE_SENTINELS, TemplateLibrary.pruneable(setOf("t")))
        assertEquals(listOf("t"), TemplateLibrary.rowIdsAmong(listOf(TemplateIds.GRID, "t")))
    }

    @Test
    fun `the search shelf ranks Blank, the built-ins and the rows together, never a folder`() {
        val cards = TemplateLibrary.searchCards("grd", "Blank", labels, listOf(row("t", "Grid paper"), row("f", "grd", folder = true)))
        assertEquals(listOf(TemplateIds.GRID, "t"), cards.map { it.id })
        assertTrue(TemplateLibrary.searchCards("bl", "Blank", labels, emptyList()).any { it is TemplateCard.Blank })
        assertTrue(TemplateLibrary.searchCards("   ", "Blank", labels, emptyList()).isEmpty())
    }
}
