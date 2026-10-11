package com.symmetricalpalmtree.soil.docsprout.editor.rich

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.symmetricalpalmtree.soil.markdown.rich.RichAttr
import com.symmetricalpalmtree.soil.markdown.rich.RichBlock
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichRules
import com.symmetricalpalmtree.soil.markdown.rich.RichSpan
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle

// The inline styles as spans of this app's own classes: a span of any other class (an input
// method's composing underline, something a paste brought) is never read as a style.
class BoldSpan : StyleSpan(Typeface.BOLD)
class ItalicSpan : StyleSpan(Typeface.ITALIC)
class StrikeSpan : StrikethroughSpan()
class CodeSpan : TypefaceSpan("monospace")

/** A link is underlined: colour is not there to say "link". Its address rides on the span. */
class LinkSpan(val url: String) : UnderlineSpan()

/**
 * [RichDoc] ↔ the spanned text a rendered editor and a page layout work on.
 *
 * As text, **a block is a paragraph**: its words, then a line break, under one [BlockSpan] that
 * covers both. Every block ends with its line break, the last one too: a layout gives an empty
 * last line no paragraph spans at all, so the line break is what lets an empty block at the end
 * still show its bullet. A rule's "words" are one zero-width character.
 */
object RichCodec {

    /**
     * What a rule block holds in place of words: a private-use character, which no document
     * holds, so taking it out of the words never takes out one that was written (a zero-width
     * space was, and pasted text carries those). It is never drawn ([BlockSpan]).
     */
    const val RULE_CHAR = '\uE000'

    /** A block span holds from its paragraph's first character, and typing at its start is typing into it. */
    const val BLOCK_FLAGS = Spanned.SPAN_INCLUSIVE_EXCLUSIVE

    /** Typing at the end of a styled run carries the style on; typing in front of it does not. */
    const val INLINE_FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    fun toSpannable(doc: RichDoc, metrics: BlockMetrics): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        // The styles go on once every word is in: a style's end takes in what is put straight
        // after it, which is right for typing and wrong for the block that follows.
        val styles = ArrayList<Triple<RichSpan, Int, Int>>()
        for (block in doc.blocks) {
            val start = sb.length
            if (block.attr.kind == RichKind.RULE) sb.append(RULE_CHAR) else sb.append(block.text.replace('\n', ' '))
            for (span in block.spans) {
                val from = (start + span.start).coerceIn(start, sb.length)
                val to = (start + span.end).coerceIn(start, sb.length)
                if (to > from) styles += Triple(span, from, to)
            }
            sb.append('\n')
            sb.setSpan(BlockSpan(block.attr.canonical(), metrics), start, sb.length, BLOCK_FLAGS)
        }
        for ((span, from, to) in styles) sb.setSpan(inlineSpan(span.style, span.url), from, to, INLINE_FLAGS)
        layoutPass(sb)
        return sb
    }

    fun fromSpanned(text: Spanned): RichDoc {
        val blocks = ArrayList<RichBlock>()
        var p = 0
        while (p < text.length) {
            val nl = TextUtils.indexOf(text, '\n', p)
            val end = if (nl < 0) text.length else nl
            val attr = blockAt(text, p)?.attr ?: RichAttr.PARAGRAPH
            if (attr.kind == RichKind.RULE) {
                blocks += RichBlock(attr)
            } else {
                val spans = ArrayList<RichSpan>()
                for (span in text.getSpans(p, end, Any::class.java)) {
                    val style = styleOf(span) ?: continue
                    val from = maxOf(text.getSpanStart(span), p) - p
                    val to = minOf(text.getSpanEnd(span), end) - p
                    if (to > from) spans += RichSpan(from, to, style, (span as? LinkSpan)?.url.orEmpty())
                }
                blocks += RichBlock(attr, text.subSequence(p, end).toString().replace(RULE_CHAR.toString(), ""), spans)
            }
            if (nl < 0) break
            p = nl + 1
        }
        return RichDoc(blocks)
    }

    fun inlineSpan(style: RichStyle, url: String = ""): Any = when (style) {
        RichStyle.BOLD -> BoldSpan()
        RichStyle.ITALIC -> ItalicSpan()
        RichStyle.STRIKE -> StrikeSpan()
        RichStyle.CODE -> CodeSpan()
        RichStyle.LINK -> LinkSpan(url)
    }

    fun styleOf(span: Any): RichStyle? = when (span) {
        is BoldSpan -> RichStyle.BOLD
        is ItalicSpan -> RichStyle.ITALIC
        is StrikeSpan -> RichStyle.STRIKE
        is CodeSpan -> RichStyle.CODE
        is LinkSpan -> RichStyle.LINK
        else -> null
    }

    fun spanClass(style: RichStyle): Class<*> = when (style) {
        RichStyle.BOLD -> BoldSpan::class.java
        RichStyle.ITALIC -> ItalicSpan::class.java
        RichStyle.STRIKE -> StrikeSpan::class.java
        RichStyle.CODE -> CodeSpan::class.java
        RichStyle.LINK -> LinkSpan::class.java
    }

    /** The block whose paragraph starts at [paragraphStart], or null. */
    fun blockAt(text: Spanned, paragraphStart: Int): BlockSpan? =
        around(text, paragraphStart, BlockSpan::class.java).firstOrNull { text.getSpanStart(it) == paragraphStart && text.getSpanEnd(it) > paragraphStart }

    /**
     * The spans of [kind] at or beside [at]; the caller says which of them it means. A query of
     * no width is never made: on the Nomad it does not answer a span that starts where it asks.
     */
    fun <T> around(text: Spanned, at: Int, kind: Class<T>): Array<T> =
        text.getSpans((at - 1).coerceAtLeast(0), (at + 1).coerceAtMost(text.length), kind)

    /** Every block in document order. */
    fun blocks(text: Spanned): List<BlockSpan> =
        text.getSpans(0, text.length, BlockSpan::class.java).sortedBy { text.getSpanStart(it) }

    /**
     * What each block owes to the ones before it: an ordered item's number and the air above.
     * A block whose number or air changed is set again, on an [Editable], so its lines are laid
     * out again.
     */
    fun layoutPass(text: Spanned) {
        val blocks = blocks(text)
        if (blocks.isEmpty()) return
        val attrs = blocks.map { it.attr }
        val numbers = RichRules.numbering(attrs)
        val tight = RichRules.tight(attrs)
        for ((i, block) in blocks.withIndex()) {
            val gap = when {
                i == 0 -> 0
                tight[i] -> block.metrics.tightGap
                else -> block.metrics.gap
            }
            // An ordered item claims the number it shows, so what is written is what is seen.
            if (block.attr.kind == RichKind.ORDERED && block.attr.number != numbers[i]) block.attr = block.attr.copy(number = numbers[i])
            if (block.number != numbers[i] || block.gapTop != gap) {
                block.number = numbers[i]
                block.gapTop = gap
                (text as? Editable)?.let { refresh(it, block) }
            }
        }
    }

    /** Set a block span again where it stands: the way a layout learns its drawing changed. */
    fun refresh(text: Editable, block: BlockSpan) {
        val start = text.getSpanStart(block)
        val end = text.getSpanEnd(block)
        if (start >= 0 && end >= start) text.setSpan(block, start, end, BLOCK_FLAGS)
    }
}
