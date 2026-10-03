package com.symmetricalpalmtree.soil.library

import com.symmetricalpalmtree.soil.data.index.Assignment
import com.symmetricalpalmtree.soil.data.index.Folder
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.index.TagRecord
import com.symmetricalpalmtree.soil.paper.core.FuzzyRank

/**
 * What the search shelf holds, and in what order: pure and JVM-tested. Folders, then items,
 * then pages; relevance orders each group and never outranks the kind.
 *
 * **One query, two kinds of answer.** A name matches, and so does a tag; both rank through the
 * same matcher, so `mtg` finds a notebook called "Meeting Notes" and one tagged `meeting` in one
 * list. An item matching both ways appears once, at its better rank. **A page hit is its own
 * card**: a tag on page 3 is not a fact about the notebook, it is the page you want back.
 *
 * Two steps, because the read is two queries: [matchTags] says which tags the query touches,
 * and the caller fetches the assignments of those alone before [rank].
 *
 * Dead assignments never surface: tags are read through the library's own live item listing.
 */
object SearchMerge {

    /** An item on the shelf, and the tag that put it here, only when its name did not match. */
    class ItemHit(val item: Item, val matchedTag: String?)

    /** One page on the shelf: which item holds it, which page, and the tag that matched. */
    class PageHit(val item: Item, val pageId: String, val matchedTag: String)

    class Shelf(val folders: List<Folder>, val items: List<ItemHit>, val pages: List<PageHit>) {
        val isEmpty: Boolean get() = folders.isEmpty() && items.isEmpty() && pages.isEmpty()
    }

    /** Which of the library's tags answer a query, and how well; [display] holds every tag. */
    class TagMatches(val display: Map<String, String>, val match: Map<String, FuzzyRank.Match>) {
        val ids: Set<String> = match.keys
    }

    fun matchTags(tags: List<TagRecord>, query: String): TagMatches {
        val display = HashMap<String, String>(tags.size * 2)
        val match = HashMap<String, FuzzyRank.Match>()
        for (tag in tags) {
            display[tag.id] = tag.display
            FuzzyRank.match(tag.display, query)?.let { match[tag.id] = it }
        }
        return TagMatches(display, match)
    }

    fun rank(
        folders: List<Folder>,
        items: List<Item>,
        query: String,
        tags: TagMatches? = null,
        assignments: List<Assignment> = emptyList(),
    ): Shelf {
        if (!FuzzyRank.isRunnable(query)) return Shelf(emptyList(), emptyList(), emptyList())
        val rankedFolders = FuzzyRank.rank(folders, query) { it.name }
        if (tags == null) return Shelf(rankedFolders, FuzzyRank.rank(items, query) { it.name }.map { ItemHit(it, null) }, emptyList())

        val matching = tags.match
        val byItem = HashMap<String, ArrayList<Assignment>>()
        if (matching.isNotEmpty()) {
            for (a in assignments) {
                if (a.tagId !in matching) continue
                byItem.getOrPut(a.itemId) { ArrayList() } += a
            }
        }
        val itemHits = ArrayList<Candidate<ItemHit>>()
        val pageHits = ArrayList<Candidate<PageHit>>()
        for (item in items) {
            val nameMatch = FuzzyRank.match(item.name, query)
            val mine = byItem[item.id]
            val bestOwnTag = mine
                ?.filter { it.isItemTag }
                ?.mapNotNull { a -> tags.display[a.tagId]?.let { it to matching.getValue(a.tagId) } }
                ?.minWithOrNull(compareBy({ it.second }, { it.first.length }, { it.first }))
            // Ranked by whichever of the two answered better; a tie goes to the name.
            val label = when {
                bestOwnTag == null -> item.name.takeIf { nameMatch != null }
                nameMatch == null -> bestOwnTag.first
                nameMatch <= bestOwnTag.second -> item.name
                else -> bestOwnTag.first
            }
            if (label != null) {
                val shown = if (nameMatch == null) bestOwnTag?.first else null
                itemHits += Candidate(ItemHit(item, shown), label)
            }
            // One card per page, named by whichever of its tags answered best.
            mine?.filterNot { it.isItemTag }
                ?.groupBy { it.pageId }
                ?.forEach { (pageId, rows) ->
                    val best = rows
                        .mapNotNull { a -> tags.display[a.tagId]?.let { it to matching.getValue(a.tagId) } }
                        .minWithOrNull(compareBy({ it.second }, { it.first.length }, { it.first }))
                        ?: return@forEach
                    pageHits += Candidate(PageHit(item, pageId, best.first), best.first)
                }
        }
        return Shelf(
            folders = rankedFolders,
            items = FuzzyRank.rank(itemHits, query) { it.label }.map { it.value },
            pages = FuzzyRank.rank(pageHits, query) { it.label }.map { it.value },
        )
    }

    private class Candidate<T>(val value: T, val label: String)
}
