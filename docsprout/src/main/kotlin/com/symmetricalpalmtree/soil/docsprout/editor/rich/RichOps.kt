package com.symmetricalpalmtree.soil.docsprout.editor.rich

import android.text.Editable
import android.text.Spanned
import com.symmetricalpalmtree.soil.markdown.TextSearch
import com.symmetricalpalmtree.soil.markdown.rich.RichAttr
import com.symmetricalpalmtree.soil.markdown.rich.RichBlock
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichRules
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle

/**
 * What the format tools do to a rendered document. Each is one step to undo, acts on the
 * selection (or, with none, on the word or block the caret is in), and tells the editor's
 * listener once.
 */
internal object RichOps {

    // ── Inline styles ──────

    /**
     * Bold, italic, strikethrough or code over the selection: taken off when all of it already
     * has the style, put on otherwise. With nothing selected it is the word the caret is inside;
     * with the caret between words, it is whatever is typed next.
     */
    fun toggleInline(view: RichEditText, style: RichStyle) {
        val s = view.text ?: return
        var a = view.selectionStart.coerceAtLeast(0)
        var b = view.selectionEnd.coerceAtLeast(0)
        if (a > b) a = b.also { b = a }
        if (a == b) {
            val start = view.paragraphStart(s, a)
            val end = wordsEnd(view, s, a)
            // A caret at either edge of a word is between words: only one inside a word takes it.
            val word = RichRules.wordAt(s.subSequence(start, end), a - start)?.takeIf { a - start > it.first && a - start < it.second }
            if (word == null) {
                val on = view.pendingFor(style, a) ?: styledAt(s, a, style)
                view.setPending(style, !on, a)
                return
            }
            a = start + word.first
            b = start + word.second
        }
        val pieces = pieces(view, s, a, b)
        if (pieces.isEmpty()) return
        val all = pieces.all { (from, to) -> covered(s, from, to, style) }
        view.beforeTool()
        for ((from, to) in pieces) if (all) removeStyle(s, from, to, style) else addStyle(s, from, to, style, "")
        view.edited(words = false)
    }

    /** The address of the link at the caret or over the selection, or null. */
    fun linkAt(view: RichEditText): String? = linkSpanAt(view)?.url

    /** The words of the link at the caret or over the selection, or null. */
    fun linkWordsAt(view: RichEditText): String? {
        val s = view.text ?: return null
        val link = linkSpanAt(view) ?: return null
        return s.subSequence(s.getSpanStart(link), s.getSpanEnd(link)).toString()
    }

    private fun linkSpanAt(view: RichEditText): LinkSpan? {
        val s = view.text ?: return null
        val a = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val b = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val links = if (b > a) s.getSpans(a, b, LinkSpan::class.java) else RichCodec.around(s, a, LinkSpan::class.java)
        return links.firstOrNull { s.getSpanStart(it) <= a && s.getSpanEnd(it) >= b }
    }

    /** `[from, to)` a link to [url] and to nothing else: any other link over it is taken off first. */
    fun linkOver(s: Editable, from: Int, to: Int, url: String) {
        removeStyle(s, from, to, RichStyle.LINK)
        addStyle(s, from, to, RichStyle.LINK, url)
    }

    /**
     * Make the selection a link to [url]; with nothing selected, the link the caret is in, else
     * the word at the caret, else [words] are put in to carry it (the address itself, or the
     * name of what it points at). An empty [url] takes the link off.
     */
    fun setLink(view: RichEditText, url: String, words: String = url) {
        val s = view.text ?: return
        var a = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        var b = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        if (a == b) {
            val inside = RichCodec.around(s, a, LinkSpan::class.java).firstOrNull { s.getSpanStart(it) <= a && s.getSpanEnd(it) >= a }
            if (inside != null) {
                a = s.getSpanStart(inside)
                b = s.getSpanEnd(inside)
            } else {
                val start = view.paragraphStart(s, a)
                val word = RichRules.wordAt(s.subSequence(start, wordsEnd(view, s, a)), a - start)
                if (word != null) {
                    a = start + word.first
                    b = start + word.second
                }
            }
        }
        if (a == b && url.isEmpty()) return
        view.beforeTool()
        if (a == b) {
            val at = a
            val put = words.replace('\n', ' ').ifBlank { url }
            view.edit { it.insert(at, put) }
            b = at + put.length
        }
        for ((from, to) in pieces(view, s, a, b)) {
            removeStyle(s, from, to, RichStyle.LINK)
            if (url.isNotEmpty()) addStyle(s, from, to, RichStyle.LINK, url)
        }
        view.setSelection(b.coerceAtMost(s.length))
        view.edited(words = false)
    }

    /** Whether what is typed at [at] would take [style]: the caret is inside a run of it, or at its end. */
    private fun styledAt(s: Spanned, at: Int, style: RichStyle): Boolean =
        RichCodec.around(s, at, RichCodec.spanClass(style)).any { s.getSpanStart(it) < at && s.getSpanEnd(it) >= at }

    /** Where the words of the paragraph holding [at] end: its line break is not one of them. */
    private fun wordsEnd(view: RichEditText, s: CharSequence, at: Int): Int {
        val end = view.paragraphEnd(s, at)
        return if (end > 0 && end <= s.length && s[end - 1] == '\n') end - 1 else end
    }

    /** `[a, b)` as the stretch of each paragraph's words it covers: a style never sits on a line break. */
    private fun pieces(view: RichEditText, s: CharSequence, a: Int, b: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var p = view.paragraphStart(s, a)
        while (p < s.length && p < b) {
            val end = view.paragraphEnd(s, p)
            val from = maxOf(a, p)
            val to = minOf(b, wordsEnd(view, s, p))
            if (to > from) out += from to to
            if (end <= p) break
            p = end
        }
        return out
    }

    private fun covered(s: Spanned, from: Int, to: Int, style: RichStyle): Boolean {
        var reached = from
        for (span in s.getSpans(from, to, RichCodec.spanClass(style)).sortedBy { s.getSpanStart(it) }) {
            if (s.getSpanStart(span) > reached) return false
            reached = maxOf(reached, s.getSpanEnd(span))
            if (reached >= to) return true
        }
        return reached >= to
    }

    /** [style] over `[from, to)`, joined with any run of it that touches. */
    fun addStyle(s: Editable, from: Int, to: Int, style: RichStyle, url: String, flags: Int = RichCodec.INLINE_FLAGS) {
        if (to <= from) return
        var start = from
        var end = to
        for (span in s.getSpans(from, to, RichCodec.spanClass(style))) {
            if (span is LinkSpan && span.url != url) continue
            start = minOf(start, s.getSpanStart(span))
            end = maxOf(end, s.getSpanEnd(span))
            s.removeSpan(span)
        }
        s.setSpan(RichCodec.inlineSpan(style, url), start, end, flags)
    }

    /**
     * No style sits on a line break. A run that has taken one in (Enter at its end, lines pasted
     * into it) is cut there: what is before the break keeps the style, what is after it and
     * already there keeps it too, and the break itself, with whatever is typed after it on the
     * new line, has none. Without this a style at the end of a line runs on down the document.
     */
    fun keepOffLineBreaks(s: Editable, from: Int, to: Int) {
        var nl = android.text.TextUtils.indexOf(s, '\n', from.coerceIn(0, s.length), to.coerceIn(0, s.length))
        while (nl >= 0 && nl < to) {
            for (span in s.getSpans(nl, nl + 1, Any::class.java)) {
                val style = RichCodec.styleOf(span) ?: continue
                val start = s.getSpanStart(span)
                val end = s.getSpanEnd(span)
                if (start > nl || end <= nl) continue
                val url = (span as? LinkSpan)?.url.orEmpty()
                s.removeSpan(span)
                if (start < nl) s.setSpan(RichCodec.inlineSpan(style, url), start, nl, RichCodec.INLINE_FLAGS)
                if (end > nl + 1) s.setSpan(RichCodec.inlineSpan(style, url), nl + 1, end, RichCodec.INLINE_FLAGS)
            }
            nl = android.text.TextUtils.indexOf(s, '\n', nl + 1, to.coerceIn(0, s.length))
        }
    }

    /** [style] off `[from, to)`: a run that reaches past either end keeps what is outside. */
    fun removeStyle(s: Editable, from: Int, to: Int, style: RichStyle) {
        if (to <= from) return
        for (span in s.getSpans(from, to, RichCodec.spanClass(style))) {
            val start = s.getSpanStart(span)
            val end = s.getSpanEnd(span)
            if (end <= from || start >= to) continue
            val url = (span as? LinkSpan)?.url.orEmpty()
            s.removeSpan(span)
            if (start < from) s.setSpan(RichCodec.inlineSpan(style, url), start, from, RichCodec.INLINE_FLAGS)
            if (end > to) s.setSpan(RichCodec.inlineSpan(style, url), to, end, RichCodec.INLINE_FLAGS)
        }
    }

    // ── Blocks ──────

    /** The blocks the selection touches. A selection that ends at the very start of a block does not touch it. */
    private fun touched(view: RichEditText, s: Editable): List<BlockSpan> {
        val a = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val b = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val out = ArrayList<BlockSpan>()
        var p = view.paragraphStart(s, a)
        while (p < s.length) {
            if (p > a && p >= b) break
            RichCodec.blockAt(s, p)?.let { out += it }
            p = view.paragraphEnd(s, p)
        }
        return out
    }

    /** A document with no words has no block to act on yet: give it its first. */
    private fun ensureBlock(view: RichEditText) {
        if (view.text.isNullOrEmpty()) view.edit { it.append('\n') }
    }

    /** A block tool: heading, quote, one of the lists, or back to a paragraph. */
    fun setBlock(view: RichEditText, kind: RichKind, level: Int = 0) {
        ensureBlock(view)
        val s = view.text ?: return
        val blocks = touched(view, s).filter { it.attr.kind != RichKind.RULE }
        if (blocks.isEmpty()) return
        val next = RichRules.retarget(blocks.map { it.attr }, kind, level)
        view.beforeTool()
        blocks.forEachIndexed { i, block -> if (block.attr != next[i]) view.setAttr(s, block, next[i]) }
        RichCodec.layoutPass(s)
        view.edited(words = false)
    }

    /** Move the list items the selection touches in or out a level. Answers whether any was a list item. */
    fun indent(view: RichEditText, delta: Int): Boolean {
        val s = view.text ?: return false
        val items = touched(view, s).filter { it.attr.isList }
        if (items.isEmpty()) return false
        val moved = items.filter { RichRules.indent(it.attr, delta) != it.attr }
        if (moved.isEmpty()) return true
        view.beforeTool()
        for (block in moved) view.setAttr(s, block, RichRules.indent(block.attr, delta))
        RichCodec.layoutPass(s)
        view.edited(words = false)
        return true
    }

    /**
     * A rule under the block the caret is in (an empty paragraph becomes the rule itself), with
     * a paragraph after it to go on writing in when the rule would be the document's last block.
     */
    fun insertRule(view: RichEditText) {
        view.beforeTool()
        val blocks = ArrayList(view.document().blocks)
        val index = if (blocks.isEmpty()) -1 else view.blockIndexAt(view.selectionEnd.coerceAtLeast(0)).coerceAtMost(blocks.size - 1)
        val rule = RichBlock(RichAttr(RichKind.RULE))
        val ruleAt = when {
            index < 0 -> { blocks += rule; 0 }
            blocks[index].attr.kind == RichKind.PARAGRAPH && blocks[index].text.isEmpty() -> { blocks[index] = rule; index }
            else -> { blocks.add(index + 1, rule); index + 1 }
        }
        if (ruleAt == blocks.size - 1) blocks += RichBlock()
        view.replaceDocument(RichDoc(blocks), caretBlock = ruleAt + 1)
        view.edited(words = false)
    }

    /**
     * Plain words put in at the caret, over the selection, with [selectFrom]..[selectTo] of them
     * left selected. They are plain: a style the caret stood in or at the end of does not take
     * them in, and none runs on over a line break among them.
     */
    fun insertText(view: RichEditText, words: String, selectFrom: Int, selectTo: Int) {
        val s = view.text ?: return
        val a = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val b = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        view.beforeTool()
        view.edit {
            it.replace(a, b, words)
            val end = (a + words.length).coerceAtMost(it.length)
            for (style in RichStyle.values()) removeStyle(it, a, end, style)
            keepOffLineBreaks(it, a, end)
        }
        view.setSelection((a + selectFrom).coerceAtMost(s.length), (a + selectTo).coerceAtMost(s.length))
        view.edited(words = false)
    }

    /** Every match of [query] replaced, last first so no offset moves under the ones still to do, as one step to undo. */
    fun replaceAll(view: RichEditText, query: String, replacement: String): Int {
        val s = view.text ?: return 0
        val matches = TextSearch.matches(s.toString(), query)
        if (matches.isEmpty()) return 0
        view.asOneEdit {
            view.edit { text -> for (m in matches.asReversed()) text.replace(m.start, m.end, replacement) }
        }
        view.edited(words = false)
        return matches.size
    }
}
