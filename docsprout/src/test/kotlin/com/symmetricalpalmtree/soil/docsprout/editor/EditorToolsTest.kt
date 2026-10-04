package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter.Renumber
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorToolsTest {

    @Test
    fun `a caret after a rewritten marker moves by what the marker grew or shrank`() {
        // "10." became "3." before the caret: one character fewer.
        assertEquals(19, EditorTools.caretAfterRenumber(listOf(Renumber(at = 4, length = 3, marker = "3.")), 20))
    }

    @Test
    fun `a caret before every rewrite stays where it is`() {
        assertEquals(2, EditorTools.caretAfterRenumber(listOf(Renumber(at = 4, length = 3, marker = "3.")), 2))
    }

    @Test
    fun `a caret inside a rewritten marker lands at the end of the new one`() {
        assertEquals(6, EditorTools.caretAfterRenumber(listOf(Renumber(at = 4, length = 3, marker = "3.")), 5))
    }
}
