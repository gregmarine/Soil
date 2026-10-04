package com.symmetricalpalmtree.soil.markdown.rich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichTypingTest {

    private fun line(typed: String, attr: RichAttr = RichAttr.PARAGRAPH) = RichTyping.lineStart(typed, attr)?.let { it.length to it.attr }
    private fun pair(typed: String) = RichTyping.pairClosed(typed)?.let { Triple(it.openStart, it.markerLength, it.style) }

    @Test
    fun `a marker and its space at the start of a paragraph make the block`() {
        assertEquals(2 to RichAttr.heading(1), line("# "))
        assertEquals(4 to RichAttr.heading(3), line("### "))
        assertEquals(2 to RichAttr(RichKind.BULLET), line("- "))
        assertEquals(2 to RichAttr(RichKind.BULLET), line("* "))
        assertEquals(2 to RichAttr(RichKind.BULLET), line("+ "))
        assertEquals(3 to RichAttr(RichKind.ORDERED, number = 1), line("1. "))
        assertEquals(4 to RichAttr(RichKind.ORDERED, number = 12), line("12. "))
        assertEquals(2 to RichAttr(RichKind.QUOTE), line("> "))
    }

    @Test
    fun `a box and its space at the start of a bullet make a task`() {
        assertEquals(4 to RichAttr(RichKind.TASK, depth = 1), line("[ ] ", RichAttr(RichKind.BULLET, depth = 1)))
        assertEquals(4 to RichAttr(RichKind.TASK, checked = true), line("[x] ", RichAttr(RichKind.BULLET)))
        assertNull(line("[ ] "))
    }

    @Test
    fun `anything else at the start of a block is words`() {
        assertNull(line("####### "))
        assertNull(line("#"))
        assertNull(line("a # "))
        assertNull(line("-- "))
        assertNull(line("1) "))
        assertNull(line("# ", RichAttr(RichKind.QUOTE)))
        assertNull(line("- ", RichAttr.heading(1)))
        assertNull(line("- ", RichAttr(RichKind.BULLET)))
    }

    @Test
    fun `a closed pair styles the words inside it`() {
        assertEquals(Triple(2, 2, RichStyle.BOLD), pair("a **bold**"))
        assertEquals(Triple(0, 2, RichStyle.BOLD), pair("__bold__"))
        assertEquals(Triple(2, 1, RichStyle.ITALIC), pair("a *it*"))
        assertEquals(Triple(2, 1, RichStyle.ITALIC), pair("a _it_"))
        assertEquals(Triple(0, 2, RichStyle.STRIKE), pair("~~gone and more~~"))
        assertEquals(Triple(4, 1, RichStyle.CODE), pair("run `a * b`"))
    }

    @Test
    fun `the first closing star of a bold pair is not an italic`() {
        assertNull(pair("**bold*"))
        assertEquals(Triple(0, 2, RichStyle.BOLD), pair("**bold**"))
    }

    @Test
    fun `what only looks like a pair is left as typed`() {
        assertNull(pair("2 * 3 *"))
        assertNull(pair("a * b*"))
        assertNull(pair("*a *"))
        assertNull(pair("**"))
        assertNull(pair("****"))
        assertNull(pair("snake_case_"))
        assertNull(pair("a~b~"))
        assertNull(pair("``"))
        assertNull(pair("` `"))
        assertNull(pair("***x***"))
        assertNull(pair("no marker"))
        assertNull(pair(""))
    }

    @Test
    fun `the nearest opener is the one that closes`() {
        assertEquals(Triple(4, 1, RichStyle.ITALIC), pair("*a* *b*"))
        assertEquals(Triple(8, 2, RichStyle.BOLD), pair("**a** x **b**"))
    }
}
