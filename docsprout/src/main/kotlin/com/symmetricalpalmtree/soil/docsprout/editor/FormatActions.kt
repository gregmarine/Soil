package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichOps
import com.symmetricalpalmtree.soil.markdown.EditableBuffer
import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter
import com.symmetricalpalmtree.soil.markdown.TextBuffer
import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * One [FormatTool] applied to whichever surface is in use.
 *
 * **In the rendered document** a tool changes what the words are ([RichOps]): no character is
 * written, and each use is one step of the rendered editor's own undo.
 *
 * **In the Markdown source** a tool is `:markdown`'s [MarkdownFormatter] run over the selection,
 * through the live `Editable`, which is what makes the field's own Ctrl+Z take it back. Each
 * operation hands back the selection it wants afterwards: a toggle that put four characters in
 * front of the line has to move the caret with them.
 *
 * Search, Word count and Reflow act on the screen or the whole text, and are routed straight
 * back out through the callbacks.
 */
internal class FormatActions(
    private val binding: ActivityDocumentBinding,
    private val rendered: () -> Boolean,
    private val onSearch: () -> Unit,
    private val onWordCount: () -> Unit,
    private val onReflow: () -> Unit,
    private val onProofread: () -> Unit,
    private val onPasteInk: () -> Unit,
    private val onBiblePassage: () -> Unit,
    /** Ask where a link points, given the address in place (or null). The answer is applied: an
     *  address, and the words to carry it when nothing is selected to carry it. */
    private val askLink: (current: String?, apply: (url: String, words: String) -> Unit) -> Unit,
) {

    fun run(tool: FormatTool) {
        when (tool) {
            FormatTool.SEARCH -> onSearch()
            FormatTool.WORD_COUNT -> onWordCount()
            FormatTool.REFLOW -> onReflow()
            FormatTool.PROOFREAD -> onProofread()
            FormatTool.PASTE_INK -> onPasteInk()
            FormatTool.BIBLE_PASSAGE -> onBiblePassage()
            FormatTool.HEADING -> askHeading()
            else -> if (rendered()) rich(tool) else source(tool)
        }
    }

    /**
     * The bar's one Heading button (Greg, 2026-10-10): a sheet of the six levels, each with its
     * glyph and its chord, and the pick is [block] exactly as the chord would be. The sheet is
     * a dialog, so the editor keeps its selection under it.
     */
    private fun askHeading() {
        val ctx = binding.root.context
        val sheet = ActionSheetDialog(ctx).title(ctx.getString(R.string.fmt_heading))
        HEADINGS.forEachIndexed { i, (icon, hint) ->
            sheet.addAction(icon, ctx.getString(hint)) { block(MarkdownFormatter.Block.HEADING, i + 1) }
        }
        sheet.show()
    }

    /** A block by kind, from a chord or the heading sheet: paragraph has no place on the bar. */
    fun block(kind: MarkdownFormatter.Block, level: Int = 1) {
        if (!rendered()) return sourceBlock(kind, level)
        when (kind) {
            MarkdownFormatter.Block.PARAGRAPH -> RichOps.setBlock(binding.rich, RichKind.PARAGRAPH)
            MarkdownFormatter.Block.HEADING -> RichOps.setBlock(binding.rich, RichKind.HEADING, level)
            MarkdownFormatter.Block.QUOTE -> RichOps.setBlock(binding.rich, RichKind.QUOTE)
            MarkdownFormatter.Block.BULLET -> RichOps.setBlock(binding.rich, RichKind.BULLET)
            MarkdownFormatter.Block.ORDERED -> RichOps.setBlock(binding.rich, RichKind.ORDERED)
            MarkdownFormatter.Block.TASK -> RichOps.setBlock(binding.rich, RichKind.TASK)
        }
    }

    fun undo() {
        if (rendered()) binding.rich.undo() else binding.editor.onTextContextMenuItem(android.R.id.undo)
    }

    fun redo() {
        if (rendered()) binding.rich.redo() else binding.editor.onTextContextMenuItem(android.R.id.redo)
    }

    // ── The rendered document ──────

    private fun rich(tool: FormatTool) {
        val view = binding.rich
        when (tool) {
            FormatTool.UNDO -> view.undo()
            FormatTool.REDO -> view.redo()
            FormatTool.BOLD -> RichOps.toggleInline(view, RichStyle.BOLD)
            FormatTool.ITALIC -> RichOps.toggleInline(view, RichStyle.ITALIC)
            FormatTool.STRIKETHROUGH -> RichOps.toggleInline(view, RichStyle.STRIKE)
            FormatTool.CODE -> RichOps.toggleInline(view, RichStyle.CODE)
            FormatTool.QUOTE -> RichOps.setBlock(view, RichKind.QUOTE)
            FormatTool.BULLET -> RichOps.setBlock(view, RichKind.BULLET)
            FormatTool.ORDERED -> RichOps.setBlock(view, RichKind.ORDERED)
            FormatTool.TASK -> RichOps.setBlock(view, RichKind.TASK)
            FormatTool.OUTDENT -> RichOps.indent(view, -1)
            FormatTool.INDENT -> RichOps.indent(view, 1)
            FormatTool.LINK -> askLink(RichOps.linkAt(view)) { url, words -> RichOps.setLink(view, url, words) }
            // An image is not drawn: it is the characters that spell it, here as in the file.
            FormatTool.IMAGE -> RichOps.insertText(view, IMAGE_SKELETON, 2, 2 + IMAGE_DESCRIPTION.length)
            FormatTool.RULE -> RichOps.insertRule(view)
            FormatTool.SEARCH, FormatTool.WORD_COUNT, FormatTool.REFLOW, FormatTool.PROOFREAD, FormatTool.PASTE_INK, FormatTool.BIBLE_PASSAGE, FormatTool.HEADING -> Unit
        }
    }

    // ── The Markdown source ──────

    private fun source(tool: FormatTool) {
        when (tool) {
            FormatTool.UNDO -> undo()
            FormatTool.REDO -> redo()
            FormatTool.BOLD -> inline("**")
            FormatTool.ITALIC -> inline("*")
            FormatTool.STRIKETHROUGH -> inline("~~")
            FormatTool.CODE -> inline("`")
            FormatTool.QUOTE -> sourceBlock(MarkdownFormatter.Block.QUOTE)
            FormatTool.BULLET -> sourceBlock(MarkdownFormatter.Block.BULLET)
            FormatTool.ORDERED -> sourceBlock(MarkdownFormatter.Block.ORDERED)
            FormatTool.TASK -> sourceBlock(MarkdownFormatter.Block.TASK)
            FormatTool.OUTDENT -> sourceIndent(-1)
            FormatTool.INDENT -> sourceIndent(1)
            FormatTool.LINK -> apply(MarkdownFormatter::insertLink)
            FormatTool.IMAGE -> apply(MarkdownFormatter::insertImage)
            FormatTool.RULE -> apply(MarkdownFormatter::insertRule)
            FormatTool.SEARCH, FormatTool.WORD_COUNT, FormatTool.REFLOW, FormatTool.PROOFREAD, FormatTool.PASTE_INK, FormatTool.BIBLE_PASSAGE, FormatTool.HEADING -> Unit
        }
    }

    private fun sourceBlock(kind: MarkdownFormatter.Block, level: Int = 1) = apply { buf, s, t ->
        MarkdownFormatter.toggleBlock(buf, s, t, kind, level)
    }

    private fun inline(marker: String) = apply { buf, s, t ->
        MarkdownFormatter.toggleInline(buf, s, t, marker)
    }

    /** Two spaces on, or up to two off, the front of every line the selection touches: a level of nesting. */
    private fun sourceIndent(delta: Int) {
        val text = binding.editor.text ?: return
        val a = binding.editor.selectionStart.coerceIn(0, text.length)
        val b = binding.editor.selectionEnd.coerceIn(0, text.length)
        fun lineStart(at: Int): Int { var p = at; while (p > 0 && text[p - 1] != '\n') p--; return p }
        // A selection that ends at the very start of a line does not touch that line.
        val last = lineStart(if (b > a && text[b - 1] == '\n') b - 1 else b)
        val starts = ArrayList<Int>()
        var p = lineStart(a)
        while (true) {
            starts += p
            if (p >= last) break
            var end = p
            while (end < text.length && text[end] != '\n') end++
            p = end + 1
        }
        // Last line first, so no offset moves under the ones still to do.
        for (start in starts.asReversed()) {
            if (delta > 0) text.insert(start, "  ")
            else {
                var n = 0
                while (n < 2 && start + n < text.length && text[start + n] == ' ') n++
                if (n > 0) text.delete(start, start + n)
            }
        }
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

    private companion object {
        /** The six levels' glyphs and hints, in order: index + 1 is the level. */
        val HEADINGS = listOf(
            PaperR.drawable.ic_h_1 to R.string.fmt_h1,
            PaperR.drawable.ic_h_2 to R.string.fmt_h2,
            PaperR.drawable.ic_h_3 to R.string.fmt_h3,
            PaperR.drawable.ic_h_4 to R.string.fmt_h4,
            PaperR.drawable.ic_h_5 to R.string.fmt_h5,
            PaperR.drawable.ic_h_6 to R.string.fmt_h6,
        )
        const val IMAGE_DESCRIPTION = "description"
        const val IMAGE_SKELETON = "![$IMAGE_DESCRIPTION](url)"
    }
}
