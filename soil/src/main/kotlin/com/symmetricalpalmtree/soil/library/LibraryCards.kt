package com.symmetricalpalmtree.soil.library

import com.symmetricalpalmtree.soil.data.index.Folder
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.paper.core.FuzzyRank
import com.symmetricalpalmtree.soil.templates.SortField
import com.symmetricalpalmtree.soil.templates.SortOrder

/** What a card in the library stands for. [subtitle] replaces the date line on a flat shelf,
 *  where "where is it" is the useful second line. */
sealed class LibraryCard(val id: String, val name: String) {
    class FolderCard(val folder: Folder, val subtitle: String? = null) : LibraryCard(folder.id, folder.name)
    class ItemCard(val item: Item, val pinned: Boolean = false, val subtitle: String? = null) : LibraryCard(item.id, item.name)
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

    /** The search shelf: folders that match, then items that match, each by relevance, from anywhere. */
    fun searchCards(query: String, folders: List<Folder>, items: List<Item>, pinned: Set<String>, placeFolder: (Folder) -> String, placeItem: (Item) -> String): List<LibraryCard> =
        FuzzyRank.rank(folders, query) { it.name }.map { LibraryCard.FolderCard(it, placeFolder(it)) } +
            FuzzyRank.rank(items, query) { it.name }.map { LibraryCard.ItemCard(it, it.id in pinned, placeItem(it)) }
}
