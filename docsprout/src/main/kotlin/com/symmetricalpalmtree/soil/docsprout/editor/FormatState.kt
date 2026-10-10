package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter

/**
 * What the format bar wears (2026-10-10): the block the caret is in, a heading's level, and the
 * inline styles the selection carries. Pure, so [selected] can be tested: which buttons read as
 * on for a state is the whole of the rule.
 */
data class FormatState(
    val block: MarkdownFormatter.Block = MarkdownFormatter.Block.PARAGRAPH,
    /** 1..6 under a heading, 0 otherwise. */
    val level: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
) {
    /** Whether [tool]'s button reads as on. Tools that are actions, not states, never do. */
    fun selected(tool: FormatTool): Boolean = when (tool) {
        FormatTool.HEADING -> block == MarkdownFormatter.Block.HEADING
        FormatTool.QUOTE -> block == MarkdownFormatter.Block.QUOTE
        FormatTool.BULLET -> block == MarkdownFormatter.Block.BULLET
        FormatTool.ORDERED -> block == MarkdownFormatter.Block.ORDERED
        FormatTool.TASK -> block == MarkdownFormatter.Block.TASK
        FormatTool.BOLD -> bold
        FormatTool.ITALIC -> italic
        FormatTool.STRIKETHROUGH -> strikethrough
        FormatTool.CODE -> code
        else -> false
    }
}
