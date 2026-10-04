package com.symmetricalpalmtree.soil.docsprout.editor.rich

import android.content.Context
import android.os.SystemClock
import android.text.Editable
import android.text.Selection
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.symmetricalpalmtree.soil.docsprout.editor.ProofreadEditText
import com.symmetricalpalmtree.soil.markdown.rich.RichAttr
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichRules
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle
import com.symmetricalpalmtree.soil.markdown.rich.RichTyping

/**
 * **The rendered editor**: a document shown as it reads and edited in place. The text holds only
 * the words; what each block is rides on its [BlockSpan], and bold, italic and the rest are
 * spans over the characters. No Markdown marker is ever in it.
 *
 * The platform does the typing. After every change this view puts the blocks back in order
 * ([reconcile]): a line break makes a new block by [RichRules.afterEnter], a join keeps the
 * block the text was joined to, and every block span ends up over exactly its paragraph. The
 * rules themselves are `:markdown`'s and JVM-tested; what is here is their application to an
 * `Editable`.
 *
 * Three things the platform cannot be left to do:
 *
 *  - **The last line break is the document's**, never the caret's: the caret is kept in front
 *    of it ([RichCodec] says why it is there).
 *  - **Backspace at the start of a block** undoes what the block is before it joins anything.
 *  - **Undo and redo** are this view's own: the platform's do not put spans back.
 *
 * The words are never logged.
 */
class RichEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ProofreadEditText(context, attrs) {

    val metrics = BlockMetrics(textSize, resources.displayMetrics.density)

    /**
     * The writer changed the document. `words` is true when characters changed (which any watcher
     * of the text has also seen), false when only a style or a block did. Never called for a [load].
     */
    var onEdited: ((words: Boolean) -> Unit)? = null

    internal fun edited(words: Boolean) { onEdited?.invoke(words) }

    /** True while this view is writing to its own text. */
    private var internal = false

    private var changeStart = 0
    private var changeBefore = 0
    private var changeCount = 0
    private var removedLineBreak = false

    /** Styles to put on, or keep off, what is typed next at [pendingAt]. */
    private val pendingOn = HashSet<RichStyle>()
    private val pendingOff = HashSet<RichStyle>()
    private var pendingAt = -1

    private val history = RichHistory()
    private var pressedTask: BlockSpan? = null

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (internal) return
                history.beforeEdit(typing = true) { snapshot() }
                removedLineBreak = count > 0 && TextUtils.indexOf(s, '\n', start, start + count) >= 0
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (internal) return
                changeStart = start
                changeBefore = before
                changeCount = count
            }

            override fun afterTextChanged(s: Editable) {
                if (internal) return
                val start = changeStart
                val count = changeCount
                val before = changeBefore
                internally {
                    ensureLastLineBreak(s)
                    applyPending(s, start, count)
                    RichOps.keepOffLineBreaks(s, start, start + count)
                    val structural = removedLineBreak || (count > 0 && TextUtils.indexOf(s, '\n', start, minOf(start + count, s.length)) >= 0) || s.length <= count + 1
                    if (count == 1 && before == 0 && start < s.length && s[start] == '\n') enter(s, start) else reconcile(s, start, start + count)
                    val fixed = fixRules(s, start, start + count)
                    // One character more than there was, at the caret: something was typed.
                    val converted = count - before == 1 && typeToFormat(s, start + count)
                    if (structural || fixed || converted) RichCodec.layoutPass(s)
                }
                edited(words = true)
            }
        })
    }

    private inline fun internally(body: () -> Unit) {
        val was = internal
        internal = true
        try { body() } finally { internal = was }
    }

    // ── The document in and out ──────

    /** Show [doc], with the caret at [caret]. Not an edit: nothing is told, and there is nothing to undo. */
    fun load(doc: RichDoc, caret: Int = 0) {
        history.clear()
        show(doc, caret, caret)
    }

    private fun show(doc: RichDoc, selStart: Int, selEnd: Int) {
        pendingAt = -1
        internally { setText(RichCodec.toSpannable(doc, metrics)) }
        val max = maxCaret()
        setSelection(selStart.coerceIn(0, max), selEnd.coerceIn(0, max))
    }

    fun document(): RichDoc = text?.let { RichCodec.fromSpanned(it) } ?: RichDoc.EMPTY

    /** The text size in sp. Every size in the document hangs off it, so it is laid out again. */
    fun setBodySize(sp: Float) {
        val doc = document()
        val a = selectionStart
        val b = selectionEnd
        textSize = sp
        metrics.em = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        show(doc, a, b)
    }

    // ── Undo ──────

    private fun snapshot() = RichHistory.Snapshot(document(), selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))

    /** Call before a change that is not typing: a tool. It is its own step to undo. */
    internal fun beforeTool() = history.beforeEdit(typing = false) { snapshot() }

    fun undo(): Boolean = history.undo({ snapshot() }) { restore(it) }
    fun redo(): Boolean = history.redo({ snapshot() }) { restore(it) }

    private fun restore(snapshot: RichHistory.Snapshot) {
        show(snapshot.doc, snapshot.selStart, snapshot.selEnd)
        edited(words = false)
    }

    /** Several changes as one step to undo: a replace-all. */
    fun asOneEdit(body: () -> Unit) {
        beforeTool()
        history.hold { body() }
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.undo -> { undo(); true }
        android.R.id.redo -> { redo(); true }
        // What is pasted is words: spans from elsewhere are not this document's styles.
        android.R.id.paste -> super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        else -> super.onTextContextMenuItem(id)
    }

    // ── The caret stays in front of the last line break ──────

    private fun maxCaret(): Int {
        val t = text ?: return 0
        return if (t.isNotEmpty() && t[t.length - 1] == '\n') t.length - 1 else t.length
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        // Called from the constructor too, before this class's own fields exist.
        @Suppress("SENSELESS_COMPARISON")
        if (history != null && text != null) {
            val max = maxCaret()
            if (selStart > max || selEnd > max) {
                setSelection(minOf(selStart, max), minOf(selEnd, max))
                return
            }
        }
        super.onSelectionChanged(selStart, selEnd)
    }

    private fun ensureLastLineBreak(s: Editable) {
        if (s.isEmpty() || s[s.length - 1] == '\n') return
        val a = Selection.getSelectionStart(s)
        val b = Selection.getSelectionEnd(s)
        s.append('\n')
        // The caret is a point, and an insert at a point pushes it on: put it back.
        if (a >= 0 && b >= 0) Selection.setSelection(s, minOf(a, s.length - 1), minOf(b, s.length - 1))
    }

    // ── Blocks kept in order ──────

    internal fun paragraphStart(s: CharSequence, at: Int): Int = TextUtils.lastIndexOf(s, '\n', at - 1) + 1

    /** Just past the paragraph's line break, or the end of the text. */
    internal fun paragraphEnd(s: CharSequence, at: Int): Int {
        val nl = TextUtils.indexOf(s, '\n', at)
        return if (nl < 0) s.length else nl + 1
    }

    /**
     * Every paragraph the change touched gets exactly one block span over exactly itself. The
     * first span that reaches a paragraph and is not already another's is its block; a paragraph
     * no span reaches is new, and is what [RichRules.afterEnter] makes of the block before it; a
     * span left with no paragraph is gone.
     */
    private fun reconcile(s: Editable, from: Int, to: Int, firstNew: RichAttr? = null) {
        if (s.isEmpty()) {
            s.getSpans(0, 0, BlockSpan::class.java).forEach { s.removeSpan(it) }
            return
        }
        var p = paragraphStart(s, (from - 1).coerceIn(0, s.length))
        var previous: BlockSpan? = if (p > 0) RichCodec.blockAt(s, paragraphStart(s, p - 1)) else null
        val claimed = HashSet<BlockSpan>()
        var newAttr = firstNew
        while (p < s.length && p <= to + 1) {
            val end = paragraphEnd(s, p)
            var owner: BlockSpan? = null
            for (span in s.getSpans(p, end, BlockSpan::class.java).sortedBy { s.getSpanStart(it) }) {
                if (span in claimed) continue
                val a = s.getSpanStart(span)
                val b = s.getSpanEnd(span)
                when {
                    b <= a -> s.removeSpan(span)
                    a >= end || b <= p -> Unit
                    owner == null -> owner = span
                    b <= end -> s.removeSpan(span)
                }
            }
            val block = owner ?: BlockSpan(newAttr ?: previous?.let { RichRules.afterEnter(it.attr) } ?: RichAttr.PARAGRAPH, metrics).also { newAttr = null }
            if (s.getSpanStart(block) != p || s.getSpanEnd(block) != end) s.setSpan(block, p, end, RichCodec.BLOCK_FLAGS)
            claimed += block
            previous = block
            p = end
        }
    }

    /**
     * A line break typed at [at]. In an empty list item or quote it ends the list or quote
     * instead of breaking the line; at the very start of a block it opens a line above and the
     * block stays what it was; anywhere else the block is broken in two by [reconcile].
     */
    private fun enter(s: Editable, at: Int) {
        val start = paragraphStart(s, at)
        val block = RichCodec.blockAt(s, start)
        val emptyBefore = at == start
        val emptyAfter = at + 1 >= s.length || s[at + 1] == '\n'
        if (block != null && emptyBefore && emptyAfter) {
            val ended = RichRules.enterOnEmpty(block.attr)
            if (ended != null) {
                s.delete(at, at + 1)
                block.attr = ended
                reconcile(s, at, at)
                RichCodec.refresh(s, block)
                RichCodec.layoutPass(s)
                return
            }
        }
        if (block != null && emptyBefore && !emptyAfter) {
            // The block's words went down a line: it goes with them, and the line above is new.
            val moved = block.attr
            block.attr = if (moved.isList || moved.kind == RichKind.QUOTE || moved.kind == RichKind.RAW) RichRules.afterEnter(moved) else RichAttr.PARAGRAPH
            reconcile(s, at, at + 1, firstNew = moved)
            RichCodec.refresh(s, block)
            return
        }
        reconcile(s, at, at + 1)
    }

    /**
     * A rule is its one character and nothing else. A rule block that has gained or lost a
     * character is a paragraph, and the rule's character anywhere else is taken out.
     */
    private fun fixRules(s: Editable, from: Int, to: Int): Boolean {
        var changed = false
        var p = paragraphStart(s, (from - 1).coerceIn(0, s.length))
        while (p < s.length && p <= to + 1) {
            var end = paragraphEnd(s, p)
            val block = RichCodec.blockAt(s, p)
            val words = s.subSequence(p, if (end > p && s[end - 1] == '\n') end - 1 else end)
            val isRule = words.length == 1 && words[0] == RichCodec.RULE_CHAR
            if (block != null && block.attr.kind == RichKind.RULE && !isRule) {
                block.attr = RichAttr.PARAGRAPH
                RichCodec.refresh(s, block)
                changed = true
            }
            if (block?.attr?.kind != RichKind.RULE) {
                var i = TextUtils.indexOf(s, RichCodec.RULE_CHAR, p, end)
                while (i >= 0) {
                    s.delete(i, i + 1)
                    end--
                    i = TextUtils.indexOf(s, RichCodec.RULE_CHAR, i, end)
                }
            }
            p = end
        }
        return changed
    }

    // ── Markdown typed in becomes what it means ──────

    /**
     * The character just typed in front of [caret] may have completed a marker at the start of
     * its block, or closed a pair around some words ([RichTyping]). The markers come out and the
     * block or the words become what they spelled. The document as typed, markers and all, is
     * kept first as its own step, so one undo puts the characters back.
     */
    private fun typeToFormat(s: Editable, caret: Int): Boolean {
        if (caret <= 0 || caret > s.length || Selection.getSelectionEnd(s) != caret || Selection.getSelectionStart(s) != caret) return false
        val typedChar = s[caret - 1]
        if (typedChar != ' ' && typedChar != '*' && typedChar != '_' && typedChar != '~' && typedChar != '`') return false
        val start = paragraphStart(s, caret)
        val block = RichCodec.blockAt(s, start) ?: return false
        if (block.attr.kind == RichKind.RAW || block.attr.kind == RichKind.RULE) return false
        val typed = s.subSequence(start, caret).toString()

        if (typedChar == ' ') {
            val made = RichTyping.lineStart(typed, block.attr) ?: return false
            history.beforeEdit(typing = false) { snapshot() }
            s.delete(start, start + made.length)
            block.attr = made.attr.canonical()
            RichCodec.refresh(s, block)
            return true
        }

        // Inside code a marker is a character.
        if (RichCodec.around(s, caret, CodeSpan::class.java).any { s.getSpanStart(it) < caret && s.getSpanEnd(it) >= caret }) return false
        val pair = RichTyping.pairClosed(typed) ?: return false
        history.beforeEdit(typing = false) { snapshot() }
        val m = pair.markerLength
        val open = start + pair.openStart
        s.delete(caret - m, caret)
        s.delete(open, open + m)
        val end = caret - 2 * m
        // The pair is closed: its end does not take in what is typed after it, whatever the
        // keyboard does between this character and the next.
        RichOps.addStyle(s, open, end, pair.style, "", Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return true
    }

    // ── Styles for what is typed next ──────

    internal fun setPending(style: RichStyle, on: Boolean, at: Int) {
        if (pendingAt != at) { pendingOn.clear(); pendingOff.clear() }
        pendingAt = at
        if (on) { pendingOn += style; pendingOff -= style } else { pendingOff += style; pendingOn -= style }
    }

    internal fun pendingFor(style: RichStyle, at: Int): Boolean? = when {
        pendingAt != at -> null
        style in pendingOn -> true
        style in pendingOff -> false
        else -> null
    }

    private fun applyPending(s: Editable, start: Int, count: Int) {
        if (pendingAt < 0) return
        if (count > 0 && start == pendingAt) {
            val end = minOf(start + count, s.length)
            for (style in pendingOff) RichOps.removeStyle(s, start, end, style)
            for (style in pendingOn) RichOps.addStyle(s, start, end, style, "")
        }
        pendingAt = -1
        pendingOn.clear()
        pendingOff.clear()
    }

    // ── Backspace at the start of a block, and Tab ──────

    private fun backspaceAtBlockStart(): Boolean {
        val s = text ?: return false
        val at = selectionStart
        if (at != selectionEnd || at < 0 || at != paragraphStart(s, at)) return false
        val block = RichCodec.blockAt(s, at) ?: return false
        val undone = RichRules.backspaceAtStart(block.attr) ?: return false
        beforeTool()
        setAttr(s, block, undone)
        RichCodec.layoutPass(s)
        edited(words = false)
        return true
    }

    internal fun setAttr(s: Editable, block: BlockSpan, attr: RichAttr) {
        block.attr = attr.canonical()
        internally { RichCodec.refresh(s, block) }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && backspaceAtBlockStart()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && backspaceAtBlockStart()) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && backspaceAtBlockStart()) return true
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed) {
            // Tab moves a list item in and Shift+Tab out. Anywhere else it is not a character
            // Markdown keeps, so it does nothing.
            RichOps.indent(this, if (event.isShiftPressed) -1 else 1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── A task's box answers a tap ──────

    private fun taskAt(event: MotionEvent): BlockSpan? {
        val s = text ?: return null
        val layout = layout ?: return null
        val x = event.x - totalPaddingLeft + scrollX
        val y = (event.y - totalPaddingTop + scrollY).toInt()
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        val lineStart = layout.getLineStart(line)
        val block = RichCodec.blockAt(s, lineStart) ?: return null
        if (block.attr.kind != RichKind.TASK) return null
        val margin = block.margin()
        return if (x >= margin - metrics.step * 1.4f && x <= margin) block else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTask = taskAt(event)
                if (pressedTask != null) return true
            }
            MotionEvent.ACTION_UP -> pressedTask?.let { pressed ->
                pressedTask = null
                val s = text
                if (s != null && taskAt(event) === pressed) {
                    beforeTool()
                    setAttr(s, pressed, pressed.attr.copy(checked = !pressed.attr.checked))
                    edited(words = false)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (pressedTask != null) { pressedTask = null; return true }
            else -> if (pressedTask != null) return true
        }
        return super.onTouchEvent(event)
    }

    // ── Where blocks are ──────

    /** Which block [offset] is in, counting from 0. */
    fun blockIndexAt(offset: Int): Int {
        val s = text ?: return 0
        var index = 0
        val stop = offset.coerceIn(0, s.length)
        for (i in 0 until stop) if (s[i] == '\n') index++
        return index
    }

    /** Where block [index] starts, or the last place a caret may stand when there is no such block. */
    fun offsetOfBlock(index: Int): Int {
        val s = text ?: return 0
        var p = 0
        var i = 0
        while (i < index) {
            val nl = TextUtils.indexOf(s, '\n', p)
            if (nl < 0 || nl + 1 >= s.length) return maxCaret().coerceAtMost(p)
            p = nl + 1
            i++
        }
        return p
    }

    /** The edit a tool makes to the text itself, with the blocks put back in order after it. */
    internal fun edit(body: (Editable) -> Unit) {
        val s = text ?: return
        internally {
            body(s)
            ensureLastLineBreak(s)
            reconcile(s, 0, s.length)
            RichCodec.layoutPass(s)
        }
        // An insert at the caret pushes the caret on, past the last line break if it was there.
        val max = maxCaret()
        if (selectionStart > max || selectionEnd > max) setSelection(minOf(selectionStart, max).coerceAtLeast(0), minOf(selectionEnd, max).coerceAtLeast(0))
    }

    /** A tool that rebuilt the document: show it, with the caret at the start of block [caretBlock]. */
    internal fun replaceDocument(doc: RichDoc, caretBlock: Int) {
        show(doc, 0, 0)
        setSelection(offsetOfBlock(caretBlock))
    }
}

/**
 * The rendered editor's own undo: whole documents, taken before each step. Typing that follows
 * typing within a moment is one step; a tool is always its own.
 */
internal class RichHistory {

    class Snapshot(val doc: RichDoc, val selStart: Int, val selEnd: Int)

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var typingUntil = 0L
    private var held = false

    fun clear() {
        undo.clear()
        redo.clear()
        typingUntil = 0L
    }

    fun beforeEdit(typing: Boolean, now: Long = SystemClock.uptimeMillis(), snapshot: () -> Snapshot) {
        if (held) return
        val continues = typing && now < typingUntil
        if (!continues) {
            undo.addLast(snapshot())
            if (undo.size > MAX_STEPS) undo.removeFirst()
            redo.clear()
        }
        typingUntil = if (typing) now + TYPING_GROUP_MS else 0L
    }

    /** Everything done inside is part of the step already taken. */
    fun hold(body: () -> Unit) {
        held = true
        try { body() } finally { held = false; typingUntil = 0L }
    }

    fun undo(current: () -> Snapshot, restore: (Snapshot) -> Unit): Boolean {
        val back = undo.removeLastOrNull() ?: return false
        redo.addLast(current())
        typingUntil = 0L
        restore(back)
        return true
    }

    fun redo(current: () -> Snapshot, restore: (Snapshot) -> Unit): Boolean {
        val forward = redo.removeLastOrNull() ?: return false
        undo.addLast(current())
        typingUntil = 0L
        restore(forward)
        return true
    }

    private companion object {
        const val MAX_STEPS = 100
        const val TYPING_GROUP_MS = 1_500L
    }
}
