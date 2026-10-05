package com.symmetricalpalmtree.soil.docsprout.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadWordsTest {

    @Test
    fun `the lines a hand wrapped join into the paragraph they are`() {
        assertEquals(listOf("The quick brown fox jumps over the lazy dog."), PadWords.paragraphsOf("The quick brown\nfox jumps over\nthe lazy dog."))
    }

    @Test
    fun `a blank line between lines is two paragraphs`() {
        assertEquals(listOf("First thought here.", "Second thought."), PadWords.paragraphsOf("First thought\nhere.\n\nSecond thought."))
    }

    @Test
    fun `stray spacing is tidied and nothing gives nothing`() {
        assertEquals(listOf("a b"), PadWords.paragraphsOf("  a   b  \n"))
        assertTrue(PadWords.paragraphsOf("").isEmpty())
        assertTrue(PadWords.paragraphsOf(" \n \n").isEmpty())
    }
}
