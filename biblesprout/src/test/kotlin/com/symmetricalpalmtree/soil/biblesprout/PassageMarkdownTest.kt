package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.bibleref.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verses-as-Markdown builder and its cap (an earlier arc "Verses"), over fake rows — no database. */
class PassageMarkdownTest {

    private fun verse(usfm: String, chapter: Int, verse: Int, text: String) = VerseRow(
        verseKey = VerseKey.encode(Canon.byUsfm(usfm).ordinal, chapter, verse),
        usfm = usfm, chapter = chapter, verse = verse, text = text,
    )

    private fun passages(wire: String) = ReferenceCodec.decode(wire)!!

    @Test
    fun `label line then numbered verses in one paragraph`() {
        val md = PassageMarkdown.build(
            "John 3:16–17",
            listOf(verse("JHN", 3, 16, "For God so loved"), verse("JHN", 3, 17, "For God did not send")),
        )
        assertEquals("**John 3:16–17**\n\n16 For God so loved 17 For God did not send", md)
    }

    @Test
    fun `a chapter crossing opens a new paragraph under its own label`() {
        val md = PassageMarkdown.build(
            "John 3:36; Proverbs 3:5",
            listOf(verse("JHN", 3, 36, "Whoever believes"), verse("PRO", 3, 5, "Trust in the LORD")),
        )
        assertEquals(
            "**John 3:36; Proverbs 3:5**\n\n36 Whoever believes\n\n**Proverbs 3**\n\n5 Trust in the LORD",
            md,
        )
    }

    @Test
    fun `the psalm title name is used for a crossing into Psalms`() {
        val md = PassageMarkdown.build(
            "x",
            listOf(verse("JHN", 1, 1, "In the beginning"), verse("PSA", 23, 1, "The LORD is my shepherd")),
        )
        assertTrue(md.contains("**Psalm 23**"))
    }

    @Test
    fun `no verses builds nothing`() {
        assertEquals("", PassageMarkdown.build("John 3:16", emptyList()))
    }
}
