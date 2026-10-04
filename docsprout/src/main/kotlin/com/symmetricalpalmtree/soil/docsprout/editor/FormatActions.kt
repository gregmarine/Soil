package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.markdown.EditableBuffer
import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter
import com.symmetricalpalmtree.soil.markdown.TextBuffer

/**
 * One [FormatTool] applied to the Markdown source: the bar's tools and the chord-only ones, all
 * of them `:markdown`'s [MarkdownFormatter] run over the current selection.
 *
 * Every operation goes through the live `Editable`, which is what makes the editor's own Ctrl+Z
 * take it back, and each one hands back the selection it wants afterwards: a toggle that put four
 * characters in front of the line has to move the caret with them, or the writer's next keystroke
 * lands in the marker.
 *
 * Three members of the enum are not formatter operations at all: Search, Word count and Reflow
 * act on the screen or the whole text, and are routed straight back out through the callbacks.
 */
internal class FormatActions(
    private val binding: ActivityDocumentBinding,
    private val onSearch: () -> Unit,
    private val onWordCount: () -> Unit,
    private val onReflow: () -> Unit,
) {

    fun run(tool: FormatTool) {
        when (tool) {
            FormatTool.H1 -> block(MarkdownFormatter.Block.HEADING, 1)
            FormatTool.H2 -> block(MarkdownFormatter.Block.HEADING, 2)
            FormatTool.H3 -> block(MarkdownFormatter.Block.HEADING, 3)
            FormatTool.BOLD -> inline("**")
            FormatTool.ITALIC -> inline("*")
            FormatTool.STRIKETHROUGH -> inline("~~")
            FormatTool.CODE -> inline("`")
            FormatTool.QUOTE -> block(MarkdownFormatter.Block.QUOTE)
            FormatTool.BULLET -> block(MarkdownFormatter.Block.BULLET)
            FormatTool.ORDERED -> block(MarkdownFormatter.Block.ORDERED)
            FormatTool.TASK -> block(MarkdownFormatter.Block.TASK)
            FormatTool.LINK -> apply(MarkdownFormatter::insertLink)
            FormatTool.IMAGE -> apply(MarkdownFormatter::insertImage)
            FormatTool.RULE -> apply(MarkdownFormatter::insertRule)
            FormatTool.SEARCH -> onSearch()
            FormatTool.WORD_COUNT -> onWordCount()
            FormatTool.REFLOW -> onReflow()
        }
    }

    /** A block toggle by kind: the chord-only paragraph and H4–H6 come in here too. */
    fun block(kind: MarkdownFormatter.Block, level: Int = 1) = apply { buf, s, t ->
        MarkdownFormatter.toggleBlock(buf, s, t, kind, level)
    }

    private fun inline(marker: String) = apply { buf, s, t ->
        MarkdownFormatter.toggleInline(buf, s, t, marker)
    }

    /** Run one formatter operation over the current selection and re-install the caret it returns. */
    private fun apply(op: (TextBuffer, Int, Int) -> MarkdownFormatter.Selection) {
        val text = binding.editor.text ?: return
        val start = binding.editor.selectionStart.coerceIn(0, text.length)
        val end = binding.editor.selectionEnd.coerceIn(0, text.length)
        val selection = op(EditableBuffer(text), start, end)
        binding.editor.setSelection(
            selection.start.coerceIn(0, text.length),
            selection.end.coerceIn(0, text.length),
        )
    }
}
