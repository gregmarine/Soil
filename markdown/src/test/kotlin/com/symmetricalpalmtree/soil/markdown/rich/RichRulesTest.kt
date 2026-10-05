package com.symmetricalpalmtree.soil.markdown.rich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RichRulesTest {

    private val bullet = RichAttr(RichKind.BULLET)
    private fun ordered(n: Int, depth: Int = 0) = RichAttr(RichKind.ORDERED, depth = depth, number = n)

    @Test
    fun `ordered items count on from the first of their run`() {
        assertEquals(listOf(3, 4, 5), RichRules.numbering(listOf(ordered(3), ordered(9), ordered(1))).toList())
    }

    @Test
    fun `a bullet at the same depth, or any other block, starts the count again`() {
        assertEquals(listOf(1, 0, 5), RichRules.numbering(listOf(ordered(1), bullet, ordered(5))).toList())
        assertEquals(listOf(1, 0, 1), RichRules.numbering(listOf(ordered(1), RichAttr.PARAGRAPH, ordered(1))).toList())
    }

    @Test
    fun `a nested list does not break the count of the list around it`() {
        assertEquals(listOf(1, 1, 2, 2), RichRules.numbering(listOf(ordered(1), ordered(1, depth = 1), ordered(1, depth = 1), ordered(7))).toList())
    }

    @Test
    fun `a list goes on after Enter, a heading does not`() {
        assertEquals(RichAttr(RichKind.TASK, depth = 1), RichRules.afterEnter(RichAttr(RichKind.TASK, depth = 1, checked = true)))
        assertEquals(RichAttr.PARAGRAPH, RichRules.afterEnter(RichAttr.heading(2)))
        assertEquals(RichAttr(RichKind.QUOTE), RichRules.afterEnter(RichAttr(RichKind.QUOTE)))
        assertEquals(RichAttr.PARAGRAPH, RichRules.afterEnter(RichAttr(RichKind.RULE)))
    }

    @Test
    fun `Enter on an empty item ends the list, a level at a time`() {
        assertEquals(RichAttr(RichKind.BULLET, depth = 0), RichRules.enterOnEmpty(RichAttr(RichKind.BULLET, depth = 1)))
        assertEquals(RichAttr.PARAGRAPH, RichRules.enterOnEmpty(bullet))
        assertEquals(RichAttr.PARAGRAPH, RichRules.enterOnEmpty(RichAttr(RichKind.QUOTE)))
        assertNull(RichRules.enterOnEmpty(RichAttr.PARAGRAPH))
        assertNull(RichRules.enterOnEmpty(RichAttr.heading(1)))
    }

    @Test
    fun `Backspace at the start of a block undoes what the block is before it joins anything`() {
        assertEquals(RichAttr.PARAGRAPH, RichRules.backspaceAtStart(RichAttr.heading(1)))
        assertEquals(RichAttr.PARAGRAPH, RichRules.backspaceAtStart(bullet))
        assertEquals(RichAttr(RichKind.ORDERED, depth = 1, number = 2), RichRules.backspaceAtStart(RichAttr(RichKind.ORDERED, depth = 2, number = 2)))
        assertNull(RichRules.backspaceAtStart(RichAttr.PARAGRAPH))
    }

    @Test
    fun `a block tool makes every touched block its kind, or paragraphs when they all are already`() {
        val mixed = listOf(RichAttr.PARAGRAPH, RichAttr(RichKind.TASK, depth = 2, checked = true))
        assertEquals(listOf(bullet, RichAttr(RichKind.BULLET, depth = 2)), RichRules.retarget(mixed, RichKind.BULLET))
        assertEquals(listOf(RichAttr.PARAGRAPH, RichAttr.PARAGRAPH), RichRules.retarget(listOf(bullet, bullet), RichKind.BULLET))
        assertEquals(listOf(RichAttr.heading(2)), RichRules.retarget(listOf(RichAttr.heading(1)), RichKind.HEADING, 2))
        assertEquals(listOf(RichAttr.PARAGRAPH), RichRules.retarget(listOf(RichAttr.heading(2)), RichKind.HEADING, 2))
        assertEquals(listOf(ordered(1)), RichRules.retarget(listOf(bullet), RichKind.ORDERED))
        assertEquals(listOf(RichAttr.PARAGRAPH), RichRules.retarget(listOf(RichAttr.heading(3)), RichKind.PARAGRAPH))
    }

    @Test
    fun `only list items move in and out`() {
        assertEquals(RichAttr(RichKind.BULLET, depth = 1), RichRules.indent(bullet, 1))
        assertEquals(bullet, RichRules.indent(bullet, -1))
        assertEquals(RichAttr.PARAGRAPH, RichRules.indent(RichAttr.PARAGRAPH, 1))
        assertEquals(RichAttr.MAX_DEPTH, RichRules.indent(RichAttr(RichKind.BULLET, depth = RichAttr.MAX_DEPTH), 1).depth)
    }

    @Test
    fun `the word at the caret, or none`() {
        assertEquals(4 to 9, RichRules.wordAt("one two's x", 6))
        assertEquals(0 to 3, RichRules.wordAt("one two", 3))
        assertNull(RichRules.wordAt("one  two", 4))
        assertNull(RichRules.wordAt("", 0))
    }
}
