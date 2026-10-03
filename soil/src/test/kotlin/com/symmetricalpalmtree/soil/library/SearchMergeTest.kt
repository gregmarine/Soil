package com.symmetricalpalmtree.soil.library

import com.symmetricalpalmtree.soil.data.index.Assignment
import com.symmetricalpalmtree.soil.data.index.Folder
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.index.TagRecord
import com.symmetricalpalmtree.soil.seam.TagRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMergeTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val p2 = "bbbbbbbb-2222-4222-8222-222222222222"

    private fun folder(id: String, name: String) = Folder(id, "", name, 0L, 0L)
    private fun item(id: String, name: String) = Item(id, "notebook", name, "GLOBAL", 0L, 0L)

    private class Tagged(val tags: List<TagRecord>, val assignments: List<Assignment>)

    private fun tagged(vararg assigns: Triple<String, String, String?>): Tagged {
        val byIdentity = LinkedHashMap<String, TagRecord>()
        val assignments = LinkedHashSet<Assignment>()
        var minted = 0
        for ((text, nb, page) in assigns) {
            val tag = byIdentity.getOrPut(TagRules.identityKey(text)) { minted++; TagRecord("%08x-1111-4111-8111-111111111111".format(minted), TagRules.display(text)) }
            assignments += Assignment(tag.id, nb, page ?: "")
        }
        return Tagged(byIdentity.values.toList(), assignments.toList())
    }

    private fun rank(folders: List<Folder>, items: List<Item>, query: String, tagged: Tagged? = null): SearchMerge.Shelf {
        if (tagged == null) return SearchMerge.rank(folders, items, query)
        val matches = SearchMerge.matchTags(tagged.tags, query)
        val fetched = tagged.assignments.filter { it.tagId in matches.ids }
        return SearchMerge.rank(folders, items, query, matches, fetched)
    }

    private fun ids(folders: List<Folder>, items: List<Item>, query: String, tagged: Tagged? = null): List<String> {
        val shelf = rank(folders, items, query, tagged)
        return shelf.folders.map { it.id } + shelf.items.map { it.item.id }
    }

    @Test
    fun `every matching folder comes before every matching item, and relevance orders each group`() {
        assertEquals(listOf("f1", n1), ids(listOf(folder("f1", "Work notes")), listOf(item(n1, "Work")), "work"))
        assertEquals(
            listOf("f2", "f1", n2, n1),
            ids(listOf(folder("f1", "Meeting Notes"), folder("f2", "Meet")), listOf(item(n1, "Amount Meeting"), item(n2, "Meeting")), "meet"),
        )
        assertEquals(listOf("f2", n2), ids(listOf(folder("f1", "Groceries"), folder("f2", "Meetings")), listOf(item(n1, "Recipes"), item(n2, "Meet")), "meet"))
        assertTrue(ids(listOf(folder("f1", "Work")), listOf(item(n1, "Work")), "   ").isEmpty())
        assertTrue(ids(emptyList(), emptyList(), "work").isEmpty())
    }

    @Test
    fun `without tags the shelf is names only`() {
        val shelf = SearchMerge.rank(emptyList(), listOf(item(n1, "Work")), "work")
        assertEquals(listOf(n1), shelf.items.map { it.item.id })
        assertNull(shelf.items.single().matchedTag)
        assertTrue(shelf.pages.isEmpty())
    }

    @Test
    fun `an item is found by a tag on it, and the tag is shown only when the name did not match`() {
        val items = listOf(item(n1, "Packing Lists"), item(n2, "Trip Journal"))
        val shelf = rank(emptyList(), items, "packing", tagged(Triple("packing", n1, null), Triple("packing", n2, null)))
        val byId = shelf.items.associateBy { it.item.id }
        assertNull(byId.getValue(n1).matchedTag)
        assertEquals("packing", byId.getValue(n2).matchedTag)
        assertEquals(2, shelf.items.size)
    }

    @Test
    fun `the better of the name and the tag decides the order`() {
        val items = listOf(item(n1, "Planning and packing kit"), item(n2, "Trip Journal"))
        val shelf = rank(emptyList(), items, "packing", tagged(Triple("packing", n2, null)))
        assertEquals(listOf(n2, n1), shelf.items.map { it.item.id })
    }

    @Test
    fun `a tagged page is its own card, one per page, beside its item's own row`() {
        val items = listOf(item(n1, "Trip Journal"))
        val alone = rank(emptyList(), items, "packing", tagged(Triple("packing", n1, p1)))
        assertTrue(alone.items.isEmpty())
        val hit = alone.pages.single()
        assertEquals(n1, hit.item.id); assertEquals(p1, hit.pageId); assertEquals("packing", hit.matchedTag)

        val twoTags = rank(emptyList(), items, "pack", tagged(Triple("pack", n1, p1), Triple("packing list", n1, p1)))
        assertEquals(1, twoTags.pages.size)
        assertEquals("pack", twoTags.pages.single().matchedTag)

        val both = rank(emptyList(), items, "packing", tagged(Triple("packing", n1, null), Triple("packing", n1, p1), Triple("packing", n1, p2)))
        assertEquals(1, both.items.size)
        assertEquals(listOf(p1, p2), both.pages.map { it.pageId }.sorted())
    }

    @Test
    fun `a tag on an item that is gone, or a tag that does not match, surfaces nothing`() {
        val gone = rank(emptyList(), emptyList(), "packing", tagged(Triple("packing", n1, null), Triple("packing", n1, p1)))
        assertTrue(gone.isEmpty)
        val other = rank(emptyList(), listOf(item(n1, "Trip Journal")), "packing", tagged(Triple("recipes", n1, p1)))
        assertTrue(other.isEmpty)
    }

    @Test
    fun `tags match fuzzily and rank by relevance`() {
        val items = listOf(item(n1, "A"), item(n2, "B"))
        val tags = tagged(Triple("meeting notes", n1, p1), Triple("mtg", n2, p2))
        assertEquals(listOf(p2, p1), rank(emptyList(), items, "mtg", tags).pages.map { it.pageId })
        assertTrue(rank(emptyList(), items, "gm", tags).pages.isEmpty())
    }
}
