package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.bibleref.*
import com.symmetricalpalmtree.soil.paper.chrome.BacklinksModel
import com.symmetricalpalmtree.soil.seam.SeamBibleBacklink

/**
 * What the Notes panel shows, as arithmetic (pure Kotlin, JVM-tested): the scope the reader is
 * in, and the rows of Soil's link index grouped into the things that cite it: a notebook page,
 * or a document. The panel itself is `:paper`'s [BacklinksPanel] (shared with the calendar's
 * Links since Calsprout, 2026-10-06); this is the wording of its rows.
 *
 * **Scope is the chapter in view, or the passage's own ranges.** A chapter is one range across
 * its whole verse band, which is why a whole-chapter link (`c:0`–`c:999`) and a single-verse
 * link are found by the same overlap; a passage names several, so the panel asks several times
 * and de-duplicates here.
 *
 * **A page is one row, whatever it holds**: a page carrying three references reads as one entry
 * naming all three, because what the reader is looking for is the page. The rows arrive in
 * reading order and the groups keep it: where the link *points* is the only order that means
 * anything while reading a chapter.
 *
 * Never logged: a wire names where the user has read and an item's name is the user's own word.
 */
object NotesModel {

    /** The panel's share of the window width — the shared panel's ([BacklinksModel]). */
    const val SIDEBAR_WIDTH_FRACTION = BacklinksModel.SIDEBAR_WIDTH_FRACTION

    fun sidebarWidthPx(windowWidthPx: Int): Int = BacklinksModel.sidebarWidthPx(windowWidthPx)

    /**
     * The verse ranges the reader is standing in. **Passage mode wins**: when [passages] is
     * non-null they are the scope, every range of every passage; otherwise the whole of
     * [chapter]'s verse band as one range. Nothing open is an empty scope: a silent no-op.
     */
    fun scope(chapter: ChapterRef?, passages: List<Passage>?): List<VerseRange> {
        if (passages != null) return passages.flatMap { it.ranges }
        val ref = chapter ?: return emptyList()
        val book = Canon.tryUsfm(ref.usfm) ?: return emptyList()
        val (start, end) = VerseKey.chapterBounds(book.ordinal, ref.chapter)
        return listOf(VerseRange(start, end))
    }

    /** One entry of the panel: everything one place, a notebook page or a document, cites in the
     *  scope. [labels] are its references in reading order; [firstStartKey] where the earliest points. */
    data class NoteGroup(
        val itemId: String,
        val pageId: String,
        val kind: String,
        val name: String,
        val pageNumber: Int,
        val labels: List<String>,
        val firstStartKey: Int,
    )

    /**
     * The rows as the panel shows them. Three collapses, in order: the same row read by two
     * scope ranges; the place ([SeamBibleBacklink.sourceItemId], [SeamBibleBacklink.sourcePageId]);
     * and the link inside a place, so a multi-range link names its reference once. A row whose
     * wire this build cannot read is dropped whole. Groups sort by where they point, then name.
     */
    fun group(rows: List<SeamBibleBacklink>): List<NoteGroup> {
        val seen = HashSet<String>(rows.size)
        val byPlace = LinkedHashMap<Pair<String, String>, MutableList<SeamBibleBacklink>>()
        for (row in rows) {
            if (!seen.add(row.linkId)) continue
            byPlace.getOrPut(row.sourceItemId to row.sourcePageId) { ArrayList() }.add(row)
        }
        val groups = ArrayList<NoteGroup>(byPlace.size)
        for ((place, keyed) in byPlace) {
            val notes = LinkedHashMap<String, Note>()
            for (row in keyed) {
                val id = linkOf(row.linkId)
                val label = ReferenceCodec.decode(row.wire)?.let { ReferenceCodec.label(it) } ?: continue
                val note = notes[id]
                if (note == null) notes[id] = Note(label, row.startKey)
                else if (row.startKey < note.firstStartKey) note.firstStartKey = row.startKey
            }
            if (notes.isEmpty()) continue
            val first = keyed.first()
            groups += NoteGroup(
                itemId = place.first,
                pageId = place.second,
                kind = first.sourceKind,
                name = first.sourceName,
                pageNumber = first.pageNumber,
                labels = notes.values.sortedBy { it.firstStartKey }.map { it.label }.distinct(),
                firstStartKey = notes.values.minOf { it.firstStartKey },
            )
        }
        groups.sortWith(compareBy<NoteGroup> { it.firstStartKey }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.pageNumber })
        return groups
    }

    /** A row's first line: the item, then where in it: `"Study · Page 4"`, or `"Sermon · Document"`
     *  for an item with no pages. The words are the caller's, so this stays free of Android. */
    fun title(group: NoteGroup, pageWord: String, documentWord: String): String =
        BacklinksModel.title(group.name, group.pageId, group.pageNumber, pageWord, documentWord)

    /** A row's second line: the references it holds. */
    fun detail(group: NoteGroup): String = group.labels.joinToString("; ")

    /** The link a range row belongs to: `<linkId>` and `<linkId>#n` are one link. */
    fun linkOf(rangeId: String): String = rangeId.substringBefore('#')

    private class Note(val label: String, var firstStartKey: Int)
}
