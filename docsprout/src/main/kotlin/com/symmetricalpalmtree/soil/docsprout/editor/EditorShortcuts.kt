package com.symmetricalpalmtree.soil.docsprout.editor

import android.view.KeyEvent
import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter

/**
 * Every `Ctrl` chord the editor answers: the format bar's tools, the chord-only ones (`Ctrl+P`
 * between the rendered document and its source, `Ctrl+0` paragraph, `Ctrl+4`–`6` the headings
 * the bar has no room for), and find / reflow.
 *
 * In the Markdown source `Ctrl+Z/Y/A/C/V/X` are deliberately **left alone**: they fall through to
 * the field, which already implements undo, redo, select-all and the clipboard. In the rendered
 * document undo and redo are the editor's own, so `Ctrl+Z`, `Ctrl+Y` and `Ctrl+Shift+Z` are
 * answered here; the rest still fall through.
 *
 * On Ratta the IME stays connected (hardware keys arrive only through it), so an input method
 * sits upstream in the key path and may claim a chord before this sees it: a reason to keep the
 * set small, not a reason to hide the keyboard.
 */
internal class EditorShortcuts(
    private val format: FormatActions,
    private val rendered: () -> Boolean,
    private val toggleMode: () -> Unit,
    private val closeOverflow: () -> Unit,
) {

    /** Answer one key event, or leave it to the system. */
    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !event.isCtrlPressed) return false
        val shift = event.isShiftPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_P -> if (!shift) { closeOverflow(); toggleMode(); return true }
            KeyEvent.KEYCODE_Z -> if (rendered()) { if (shift) format.redo() else format.undo(); return true }
            KeyEvent.KEYCODE_Y -> if (rendered() && !shift) { format.redo(); return true }
            // Paragraph and H4–H6 are chord-only: the bar stops at H3, the grammar does not.
            KeyEvent.KEYCODE_0 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.PARAGRAPH); return true }
            KeyEvent.KEYCODE_4 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.HEADING, 4); return true }
            KeyEvent.KEYCODE_5 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.HEADING, 5); return true }
            KeyEvent.KEYCODE_6 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.HEADING, 6); return true }
            KeyEvent.KEYCODE_1 -> if (!shift) return tool(FormatTool.H1)
            KeyEvent.KEYCODE_2 -> if (!shift) return tool(FormatTool.H2)
            KeyEvent.KEYCODE_3 -> if (!shift) return tool(FormatTool.H3)
            KeyEvent.KEYCODE_B -> if (!shift) return tool(FormatTool.BOLD)
            KeyEvent.KEYCODE_I -> if (!shift) return tool(FormatTool.ITALIC)
            KeyEvent.KEYCODE_X -> if (shift) return tool(FormatTool.STRIKETHROUGH)
            KeyEvent.KEYCODE_E -> if (!shift) return tool(FormatTool.CODE)
            KeyEvent.KEYCODE_Q -> if (shift) return tool(FormatTool.QUOTE)
            KeyEvent.KEYCODE_8 -> if (shift) return tool(FormatTool.BULLET)
            KeyEvent.KEYCODE_7 -> if (shift) return tool(FormatTool.ORDERED)
            KeyEvent.KEYCODE_9 -> if (shift) return tool(FormatTool.TASK)
            KeyEvent.KEYCODE_K -> return tool(if (shift) FormatTool.IMAGE else FormatTool.LINK)
            KeyEvent.KEYCODE_MINUS -> if (shift) return tool(FormatTool.RULE)
            KeyEvent.KEYCODE_F -> return tool(if (shift) FormatTool.REFLOW else FormatTool.SEARCH)
        }
        return false
    }

    /** Run a tool from a chord and claim the key. */
    private fun tool(tool: FormatTool): Boolean {
        closeOverflow()
        format.run(tool)
        return true
    }
}
