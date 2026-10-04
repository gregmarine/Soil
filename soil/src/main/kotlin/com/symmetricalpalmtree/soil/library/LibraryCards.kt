package com.symmetricalpalmtree.soil.library

import com.symmetricalpalmtree.soil.data.index.Folder
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.templates.SortField
import com.symmetricalpalmtree.soil.templates.SortOrder

/** What a card in the library stands for. [subtitle] replaces the date line on a flat shelf,
 *  where "where is it" is the useful second line. */
sealed class LibraryCard(val id: String, val name: String) {
    class FolderCard(val folder: Folder, val subtitle: String? = null) : LibraryCard(folder.id, folder.name)
    class ItemCard(val item: Item, val pinned: Boolean = false, val subtitle: String? = null) : LibraryCard(item.id, item.name)

    /** A page found by its tag: the item's cover and name, the page's number (null when the index
     *  does not know it), and the tag under the place. Opens at the page; has no sheet. */
    class PageCard(val item: Item, val pageId: String, val pageNumber: Int?, val subtitle: String) : LibraryCard(item.id + "/" + pageId, item.name)
}

/**
 * The library's listing rules, pure and JVM-tested: folders before items, the chosen order
 * within each group, names compared without case; and what the three shelves hold.
 */
object LibraryListing {

    fun sortedFolders(folders: List<Folder>, field: SortField, order: SortOrder): List<Folder> {
        val base: Comparator<Folder> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        return folders.sortedWith(if (order == SortOrder.DESC) base.reversed() else base)
    }

    fun sortedItems(items: List<Item>, field: SortField, order: SortOrder): List<Item> {
        val base: Comparator<Item> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        return items.sortedWith(if (order == SortOrder.DESC) base.reversed() else base)
    }

    /** A folder's cards: its folders first, then its items, in the sort. */
    fun folderCards(folders: List<Folder>, items: List<Item>, pinned: Set<String>, field: SortField, order: SortOrder): List<LibraryCard> =
        sortedFolders(folders, field, order).map { LibraryCard.FolderCard(it) } +
            sortedItems(items, field, order).map { LibraryCard.ItemCard(it, it.id in pinned) }

    /** The pinned shelf: the pinned items that are alive, in the sort. */
    fun pinnedCards(pinnedIds: List<String>, alive: Map<String, Item>, field: SortField, order: SortOrder, place: (Item) -> String): List<LibraryCard> =
        sortedItems(pinnedIds.mapNotNull { alive[it] }, field, order).map { LibraryCard.ItemCard(it, pinned = true, subtitle = place(it)) }

    /** The recents shelf: the items opened lately, newest first, never re-sorted. */
    fun recentCards(items: List<Item>, pinned: Set<String>, place: (Item) -> String): List<LibraryCard> =
        items.filter { it.openedAt != null }.sortedByDescending { it.openedAt }.map { LibraryCard.ItemCard(it, it.id in pinned, place(it)) }

    /**
     * The search shelf: folders that match, then items that match by name or by tag, then tagged
     * pages, each by relevance, from anywhere. An item found by its tag alone wears the tag under
     * its place; a page always does. [pageNumber] answers from the index's page order.
     */
    fun searchCards(
        shelf: SearchMerge.Shelf,
        pinned: Set<String>,
        placeFolder: (Folder) -> String,
        placeItem: (Item) -> String,
        pageNumber: (itemId: String, pageId: String) -> Int?,
        withTag: (place: String, tag: String) -> String,
    ): List<LibraryCard> =
        shelf.folders.map { LibraryCard.FolderCard(it, placeFolder(it)) } +
            shelf.items.map { hit ->
                val place = placeItem(hit.item)
                LibraryCard.ItemCard(hit.item, hit.item.id in pinned, hit.matchedTag?.let { withTag(place, it) } ?: place)
            } +
            shelf.pages.map { hit -> LibraryCard.PageCard(hit.item, hit.pageId, pageNumber(hit.item.id, hit.pageId), withTag(placeItem(hit.item), hit.matchedTag)) }
}
