package com.symmetricalpalmtree.soil.tags

import com.symmetricalpalmtree.soil.data.index.Assignment
import com.symmetricalpalmtree.soil.data.index.TagRecord
import com.symmetricalpalmtree.soil.seam.TagRules

/**
 * The tag screen's in-memory model: the library's tags and the assignments of the one item the
 * showing is about, loaded once and asked the same questions over and over. **The filter runs
 * against this, never against the store**: a keystroke repaints a list from memory and never
 * costs a read. Nothing here writes; the screen writes through the store and then reloads.
 */
class TagIndex(val tags: List<TagRecord>, val assignments: List<Assignment>) {

    private val byId: Map<String, TagRecord> = tags.associateBy { it.id }

    private val byIdentity: Map<String, TagRecord> = HashMap<String, TagRecord>(tags.size * 2).also { m ->
        // First wins: a tie-break for a hand-built index, since the stored identity is unique.
        for (t in tags) m.putIfAbsent(t.identityKey, t)
    }

    fun tag(id: String): TagRecord? = byId[id]

    /** The tag [text] names, or null: the "does this already exist?" question. */
    fun find(text: String): TagRecord? = byIdentity[TagRules.identityKey(text)]

    /** The tags ordered for reading: by identity, ties broken by the stored form. */
    fun sortedTags(): List<TagRecord> = tags.sortedWith(compareBy({ it.identityKey }, { it.display }))

    /** The tags on one target, in [sortedTags] order. [pageId] null asks about the item itself. */
    fun tagsOf(itemId: String, pageId: String? = null): List<TagRecord> {
        val ids = HashSet<String>()
        for (a in assignments) if (a.isOn(itemId, pageId)) ids += a.tagId
        return sortedTags().filter { it.id in ids }
    }

    fun isAssigned(tagId: String, itemId: String, pageId: String? = null): Boolean =
        assignments.any { it.tagId == tagId && it.isOn(itemId, pageId) }

    /**
     * The tags [query] should offer, best first: an exact identity, then prefix matches, then
     * substring matches, each group in [sortedTags] order. A blank query offers everything.
     * Deliberately not the library's fuzzy matcher: matching a name you are typing is a different
     * question from matching one you are searching for, and this one's job is to stop you
     * creating "reading list" twice.
     */
    fun suggest(query: String): List<TagRecord> {
        val key = TagRules.identityKey(query)
        val sorted = sortedTags()
        if (key.isEmpty()) return sorted
        val exact = ArrayList<TagRecord>(1)
        val prefix = ArrayList<TagRecord>()
        val infix = ArrayList<TagRecord>()
        for (t in sorted) {
            val k = t.identityKey
            when {
                k == key -> exact += t
                k.startsWith(key) -> prefix += t
                k.contains(key) -> infix += t
            }
        }
        return exact + prefix + infix
    }

    companion object {
        val EMPTY = TagIndex(emptyList(), emptyList())
    }
}
