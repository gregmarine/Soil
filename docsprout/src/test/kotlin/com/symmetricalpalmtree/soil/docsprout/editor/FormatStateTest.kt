package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which bar buttons read as on for a [FormatState]. */
class FormatStateTest {

    @Test fun `a heading lights the heading button only among the blocks`() {
        val s = FormatState(block = MarkdownFormatter.Block.HEADING, level = 3)
        assertTrue(s.selected(FormatTool.HEADING))
        for (t in listOf(FormatTool.QUOTE, FormatTool.BULLET, FormatTool.ORDERED, FormatTool.TASK)) assertFalse(t.name, s.selected(t))
    }

    @Test fun `inline styles light their own buttons and no other`() {
        val s = FormatState(bold = true, code = true)
        assertTrue(s.selected(FormatTool.BOLD))
        assertTrue(s.selected(FormatTool.CODE))
        assertFalse(s.selected(FormatTool.ITALIC))
        assertFalse(s.selected(FormatTool.STRIKETHROUGH))
    }

    @Test fun `actions never read as on`() {
        val s = FormatState(block = MarkdownFormatter.Block.TASK, bold = true, italic = true, strikethrough = true, code = true)
        for (t in listOf(FormatTool.UNDO, FormatTool.REDO, FormatTool.OUTDENT, FormatTool.INDENT, FormatTool.LINK, FormatTool.IMAGE, FormatTool.RULE, FormatTool.PASTE_INK, FormatTool.BIBLE_PASSAGE, FormatTool.SEARCH, FormatTool.WORD_COUNT, FormatTool.REFLOW, FormatTool.PROOFREAD)) assertFalse(t.name, s.selected(t))
        assertTrue(s.selected(FormatTool.TASK))
    }
}
