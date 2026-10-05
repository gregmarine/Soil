package com.symmetricalpalmtree.soil.docsprout.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPasteTest {

    @Test
    fun `the lines a hand wrapped join into the paragraph they are`() {
        assertEquals(listOf("The quick brown fox jumps over the lazy dog."), InkPaste.paragraphsOf("The quick brown\nfox jumps over\nthe lazy dog."))
    }

    @Test
    fun `a blank line between lines is two paragraphs`() {
        assertEquals(listOf("First thought here.", "Second thought."), InkPaste.paragraphsOf("First thought\nhere.\n\nSecond thought."))
    }

    @Test
    fun `stray spacing is tidied and nothing gives nothing`() {
        assertEquals(listOf("a b"), InkPaste.paragraphsOf("  a   b  \n"))
        assertTrue(InkPaste.paragraphsOf("").isEmpty())
        assertTrue(InkPaste.paragraphsOf(" \n \n").isEmpty())
    }

    @Test
    fun `words kept for a later paste read back a paragraph a line`() {
        val paragraphs = listOf("One paragraph of words.", "And another.")
        assertEquals(paragraphs, InkPaste.linesOf(paragraphs.joinToString("\n")))
    }
}
