package com.symmetricalpalmtree.soil.docsprout.editor.bible

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import com.symmetricalpalmtree.soil.docsprout.editor.ProofreadEditText
import com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadCheck
import com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadDirty
import com.symmetricalpalmtree.soil.docsprout.editor.rich.CodeSpan
import com.symmetricalpalmtree.soil.docsprout.editor.rich.LinkSpan
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichCodec
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichEditText
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichOps
import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **References linked as they are typed**: Proofread's shape, in a second subject. Edits
 * accumulate in a [ProofreadDirty] range and, after [DELAY_MS] of typing idle, the changed lines
 * are read for Bible references ([ReferenceLinker]) off the main thread; what is found becomes a
 * link, on whichever surface is in use. The whole document is read on open, after an import or
 * a paste, and on a change of surface. A result that arrives after further typing is discarded
 * and the next tick looks again.
 *
 * In the rendered document a link is a span: no character moves, the caret stays, and the link
 * is one step to undo (the words stay). In the Markdown source a link is characters, written
 * last first with the caret carried past them. A reference the caret is touching is left for a
 * tick after the caret has moved, which [onCaretMoved] asks for. A link the writer took off is
 * never put back ([unlinked]).
 *
 * Nothing here logs a word: counts only.
 */
internal class BibleLinkController(
    private val rich: RichEditText,
    private val source: ProofreadEditText,
    private val rendered: () -> Boolean,
    private val usable: () -> Boolean,
    private val scope: CoroutineScope,
    private val unlinked: () -> Set<String>,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { runPending() }
    private val dirty = ProofreadDirty()
    private var whole = false
    private var heldByCaret = false

    /** Bumped on every change: a result from an older generation never lands. */
    private var generation = 0

    init {
        for (each in listOf<ProofreadEditText>(rich, source)) {
            each.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    generation++
                    dirty.note(start, before, count)
                }
                override fun afterTextChanged(s: Editable?) { schedule() }
            })
            val was = each.onCaretMoved
            each.onCaretMoved = { was?.invoke(); if (heldByCaret) schedule() }
        }
    }

    fun dispose() = handler.removeCallbacks(tick)

    /** Something the pass concluded may no longer hold: a link taken off. */
    fun bump() { generation++ }

    /** The whole document, read: the open, an import, a paste, a change of surface. */
    fun checkDocument() {
        whole = true
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, DELAY_MS)
    }

    private fun runPending() {
        if (!usable()) return
        val editor: ProofreadEditText = if (rendered()) rich else source
        val text = editor.text ?: return
        val all = whole
        val start = dirty.start
        val end = dirty.end
        val had = !dirty.isEmpty
        whole = false
        dirty.clear()
        heldByCaret = false
        if (!all && !had) return
        val snapshot = text.toString()
        val region = if (all) ProofreadCheck.Region(0, snapshot.length) else ProofreadCheck.Region(start, end)
        val protected = if (rendered()) renderedProtected(text) else null
        val caret = editor.selectionStart.takeIf { editor.selectionStart == editor.selectionEnd && it >= 0 }
        val skip = unlinked()
        val gen = generation
        val onRendered = rendered()
        scope.launch {
            val plan = withContext(Dispatchers.Default) {
                ReferenceLinker.plan(snapshot, region, protected ?: ReferenceLinker.markdownProtected(snapshot), skip, caret)
            }
            if (gen != generation || !usable() || rendered() != onRendered) {
                // The document moved on: these offsets name nothing now. Look again.
                whole = true
                schedule()
                return@launch
            }
            heldByCaret = plan.heldByCaret
            if (plan.hits.isEmpty()) return@launch
            Slog.d(TAG) { "linked ${plan.hits.size} reference(s) ${if (onRendered) "rendered" else "source"}" }
            if (onRendered) applyRendered(rich, plan.hits) else applySource(plan.hits)
        }
    }

    private fun applySource(hits: List<ReferenceLinker.Hit>) {
        val text = source.text ?: return
        val (rewritten, caret) = ReferenceLinker.rewriteMarkdown(text.toString(), hits, source.selectionStart.coerceAtLeast(0))
        // One replace per hit, last first: the field's own undo and the watchers see each.
        for (hit in hits.sortedByDescending { it.start }) {
            text.replace(hit.start, hit.end, "[${hit.words}](${hit.address})")
        }
        if (text.toString() == rewritten) source.setSelection(caret.coerceIn(0, text.length))
    }

    /** What is not prose on the rendered surface: code, every link, and a raw block. */
    private fun renderedProtected(text: Editable): BooleanArray {
        val skip = BooleanArray(text.length)
        fun mark(from: Int, to: Int) {
            val a = from.coerceIn(0, skip.size)
            val b = to.coerceIn(a, skip.size)
            if (b > a) java.util.Arrays.fill(skip, a, b, true)
        }
        for (span in text.getSpans(0, text.length, CodeSpan::class.java)) mark(text.getSpanStart(span), text.getSpanEnd(span))
        for (span in text.getSpans(0, text.length, LinkSpan::class.java)) mark(text.getSpanStart(span), text.getSpanEnd(span))
        for (block in RichCodec.blocks(text)) if (block.attr.kind == RichKind.RAW) mark(text.getSpanStart(block), text.getSpanEnd(block))
        return skip
    }

    companion object {
        private const val TAG = "BibleLinks"
        const val DELAY_MS = 1_500L

        /** Every hit as a link span over its words: no character moves, one step to undo. */
        fun applyRendered(rich: RichEditText, hits: List<ReferenceLinker.Hit>) {
            val s = rich.text ?: return
            rich.asOneEdit {
                for (hit in hits) {
                    if (hit.end > s.length || s.subSequence(hit.start, hit.end).toString() != hit.words) continue
                    RichOps.addStyle(s, hit.start, hit.end, RichStyle.LINK, hit.address)
                }
            }
            rich.edited(words = false)
        }
    }
}
