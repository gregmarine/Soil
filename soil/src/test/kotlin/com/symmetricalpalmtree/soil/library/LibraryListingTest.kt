package com.symmetricalpalmtree.soil.library

import com.symmetricalpalmtree.soil.data.index.Folder
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.templates.SortField
import com.symmetricalpalmtree.soil.templates.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryListingTest {

    private fun folder(id: String, name: String, updated: Long = 0L) = Folder(id, "", name, 0L, updated)
    private fun item(id: String, name: String, updated: Long = 0L, opened: Long? = null) =
        Item(id, "notebook", name, "GLOBAL", 0L, updated, 1, "", opened)

    @Test
    fun `folders lead, names sort without case, and the order applies within each group`() {
        val cards = LibraryListing.folderCards(
            listOf(folder("f2", "zeta"), folder("f1", "Alpha")),
            listOf(item("b", "bravo"), item("a", "Charlie"), item("c", "alpha")),
            pinned = setOf("a"), field = SortField.NAME, order = SortOrder.ASC,
        )
        assertEquals(listOf("f1", "f2", "c", "b", "a"), cards.map { it.id })
        assertTrue((cards[4] as LibraryCard.ItemCard).pinned)
        val desc = LibraryListing.folderCards(emptyList(), listOf(item("b", "b", 2), item("a", "a", 1)), emptySet(), SortField.MODIFIED, SortOrder.DESC)
        assertEquals(listOf("b", "a"), desc.map { it.id })
    }

    @Test
    fun `the recents shelf keeps the opened order and skips what was never opened`() {
        val cards = LibraryListing.recentCards(listOf(item("a", "a", opened = 5), item("b", "b"), item("c", "c", opened = 9)), emptySet()) { "Library" }
        assertEquals(listOf("c", "a"), cards.map { it.id })
        assertEquals("Library", (cards[0] as LibraryCard.ItemCard).subtitle)
    }

    @Test
    fun `the pinned shelf lists only alive pins, in the sort`() {
        val alive = mapOf("b" to item("b", "Bravo"), "a" to item("a", "alpha"))
        val cards = LibraryListing.pinnedCards(listOf("b", "gone", "a"), alive, SortField.NAME, SortOrder.ASC) { "" }
        assertEquals(listOf("a", "b"), cards.map { it.id })
    }

    @Test
    fun `search ranks folders then items by relevance and says where each is`() {
        val shelf = SearchMerge.rank(listOf(folder("f", "Meeting Team Group")), listOf(item("i", "Amount Given"), item("j", "mtg notes")), "mtg")
        val cards = LibraryListing.searchCards(shelf, emptySet(), { "root" }, { "Work" }, { _, _ -> null }, { place, tag -> "$place · $tag" })
        assertEquals(listOf("f", "j", "i"), cards.map { it.id })
        assertEquals("Work", (cards[1] as LibraryCard.ItemCard).subtitle)
    }

    @Test
    fun `a page found by its tag is a card of its own, under the item's place and the tag`() {
        val pages = listOf(SearchMerge.PageHit(item("i", "Trip"), "p2", "packing"))
        val shelf = SearchMerge.Shelf(emptyList(), listOf(SearchMerge.ItemHit(item("j", "Journal"), "packing")), pages)
        val cards = LibraryListing.searchCards(shelf, emptySet(), { "root" }, { "Work" }, { _, pageId -> if (pageId == "p2") 2 else null }, { place, tag -> "$place · $tag" })
        assertEquals("Work · packing", (cards[0] as LibraryCard.ItemCard).subtitle)
        val page = cards[1] as LibraryCard.PageCard
        assertEquals("i/p2", page.id)
        assertEquals(2, page.pageNumber)
        assertEquals("Work · packing", page.subtitle)
    }
}
