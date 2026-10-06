package com.symmetricalpalmtree.soil.bibleref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 66-book table. It must agree, book for book and count for count, with the `book` table
 * `tools/bible/build_bible_db.py` wrote.
 */
class CanonTest {

    @Test
    fun `the canon is sixty-six books`() {
        assertEquals(66, Canon.books.size)
    }

    @Test
    fun `ordinals run one to sixty-six in order`() {
        Canon.books.forEachIndexed { index, book -> assertEquals(index + 1, book.ordinal) }
        assertEquals("GEN", Canon.books.first().usfm)
        assertEquals(1, Canon.byUsfm("GEN").ordinal)
        assertEquals("REV", Canon.books.last().usfm)
        assertEquals(66, Canon.byUsfm("REV").ordinal)
    }

    @Test
    fun `the testaments split thirty-nine and twenty-seven`() {
        assertEquals(39, Canon.books.count { it.testament == Testament.OLD })
        assertEquals(27, Canon.books.count { it.testament == Testament.NEW })
        // And the split is a clean cut, not a scatter: Malachi ends the OT.
        assertEquals("MAL", Canon.books.last { it.testament == Testament.OLD }.usfm)
        assertEquals("MAT", Canon.books.first { it.testament == Testament.NEW }.usfm)
    }

    @Test
    fun `usfm codes are unique and three characters`() {
        assertEquals(66, Canon.books.map { it.usfm }.toSet().size)
        assertTrue(Canon.books.all { it.usfm.length == 3 })
    }

    @Test
    fun `a chapter of Psalms is a Psalm and every other book names its chapters as itself`() {
        assertEquals("Psalm", Canon.chapterTitleName("PSA"))
        assertEquals("Psalm", Canon.chapterTitleName("psa"))
        assertEquals("Psalms", Canon.byUsfm("PSA").name)   // the index keeps the book's name
        assertEquals("Genesis", Canon.chapterTitleName("GEN"))
        assertEquals("Song of Solomon", Canon.chapterTitleName("SNG"))
        assertEquals("Revelation", Canon.chapterTitleName("REV"))
    }

    @Test
    fun `lookup is case-insensitive and ordinal-addressed`() {
        assertEquals("Psalms", Canon.byUsfm("psa").name)
        assertEquals("Psalms", Canon.byOrdinal(19).name)
        assertEquals("1 Corinthians", Canon.byUsfm("1CO").name)
        assertNull(Canon.tryUsfm("XXX"))
    }

    @Test
    fun `every book has its chapter count and the counts add to the Bible's`() {
        assertTrue(Canon.books.all { it.chapters >= 1 })
        assertEquals(1189, Canon.books.sumOf { it.chapters })
        assertEquals(150, Canon.chapterCount("PSA"))
        assertEquals(1, Canon.chapterCount("JUD"))
        assertEquals(1, Canon.chapterCount("phm"))
        assertEquals(0, Canon.chapterCount("XXX"))
    }

    @Test
    fun `spellings are what lookup knows`() {
        val john = Canon.byUsfm("JHN")
        assertEquals(listOf("John", "JHN", "jn", "jhn"), Canon.spellings(john))
        assertTrue(Canon.spellings(john).all { Canon.lookup(it) == john })
    }
}
