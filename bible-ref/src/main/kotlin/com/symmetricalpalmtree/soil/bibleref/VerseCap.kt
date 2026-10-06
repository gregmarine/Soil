package com.symmetricalpalmtree.soil.bibleref

/**
 * **How much of a passage may be placed on a notebook page as words**: at most [MAX_VERSES]
 * verses as the reference names them, and no whole-chapter range at all, however short the
 * chapter (Greg's rule from SN: only small references; full chapters are too big). The reader
 * itself may give a document a chapter; this cap is the page's. Pure.
 */
object VerseCap {

    /** Ten verses: about half a page at the notebook's text size. */
    const val MAX_VERSES = 10

    /**
     * Whether [passages] may be placed on a page: no whole-chapter range, and at most
     * [MAX_VERSES] verses **as named**, the difference between the endpoints, not the rows a
     * source has. A range crossing a chapter is counted by what the reference alone can know,
     * its last chapter's verses plus one; the rows read decide the rest ([rowsWithin]).
     */
    fun withinCap(passages: List<Passage>): Boolean {
        var verses = 0
        for (passage in passages) {
            for (range in passage.ranges) {
                val sv = VerseKey.verseOf(range.startKey)
                val ev = VerseKey.verseOf(range.endKey)
                if (sv == 0 || ev == VerseKey.MAX_VERSE) return false
                val crossing = VerseKey.chapterOf(range.startKey) != VerseKey.chapterOf(range.endKey)
                verses += if (crossing) ev + 1 else ev - sv + 1
                if (verses > MAX_VERSES) return false
            }
        }
        return verses in 1..MAX_VERSES
    }

    /** The cap over the verses actually read: the exact answer for a chapter-crossing range. */
    fun rowsWithin(verseCount: Int): Boolean = verseCount in 1..MAX_VERSES
}
