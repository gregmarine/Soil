package com.symmetricalpalmtree.soil.docsprout.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCoverTest {

    @Test
    fun `a short document is its own opening`() {
        assertEquals("", TextCover.opening(""))
        assertEquals("# Title\n\nWords.", TextCover.opening("# Title\n\nWords."))
    }

    @Test
    fun `the opening stops at sixty lines`() {
        val text = (1..100).joinToString("\n") { "line $it" }
        val opening = TextCover.opening(text)
        assertEquals(60, opening.count { it == '\n' })
        assertEquals("line 60\n", opening.substring(opening.length - 8))
    }

    @Test
    fun `the opening stops at two thousand characters`() {
        assertEquals(2000, TextCover.opening("a".repeat(5000)).length)
    }
}
