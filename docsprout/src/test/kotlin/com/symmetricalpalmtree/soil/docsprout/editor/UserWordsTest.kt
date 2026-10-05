package com.symmetricalpalmtree.soil.docsprout.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserWordsTest {

    @Test
    fun `words read back in the order they were added`() {
        val words = linkedSetOf("zebra", "apple", "o'clock")
        assertEquals(words.toList(), UserWords.decode(UserWords.encode(words)).toList())
    }

    @Test
    fun `nothing stored is no words`() {
        assertTrue(UserWords.decode(null).isEmpty())
        assertTrue(UserWords.decode("").isEmpty())
        assertEquals("", UserWords.encode(emptyList()))
    }

    @Test
    fun `a word that could not be stored on one line is not stored`() {
        assertEquals("a\nb", UserWords.encode(listOf("a", "", "x\ny", "b")))
    }
}
