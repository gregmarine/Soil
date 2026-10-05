package com.symmetricalpalmtree.soil.markdown.rich

/**
 * **A document as a rendered editor holds it**: blocks of plain text, each with what kind of
 * block it is and the styles laid over its characters. No Markdown marker appears anywhere in
 * it; [RichParse] reads Markdown into this and [RichWrite] writes it back.
 *
 * The grammar is `:markdown`'s closed set and nothing more: headings, paragraphs, the three
 * lists, quotes, rules; bold, italic, strikethrough, inline code, links. What Markdown has
 * beyond that is kept, never understood: a table row or a fenced line is a [RichKind.RAW] block
 * holding the line exactly as written, and an image stays the characters that spell it.
 *
 * Pure Kotlin: no android imports, safe on any thread.
 */
data class RichDoc(val blocks: List<RichBlock>) {
    companion object {
        val EMPTY = RichDoc(emptyList())
    }
}

enum class RichKind { PARAGRAPH, HEADING, BULLET, ORDERED, TASK, QUOTE, RULE, RAW }

/**
 * What a block is, apart from its words. Each field means something for one kind only and is
 * zero for the rest: [level] 1..6 for a heading, [depth] for a list item, [checked] for a task,
 * and [number] for an ordered item (the number it shows; the first of a run sets the start).
 */
data class RichAttr(
    val kind: RichKind = RichKind.PARAGRAPH,
    val level: Int = 0,
    val depth: Int = 0,
    val checked: Boolean = false,
    val number: Int = 0,
) {
    val isList: Boolean get() = kind == RichKind.BULLET || kind == RichKind.ORDERED || kind == RichKind.TASK

    /** The same attributes with every field that means nothing for [kind] put to zero. */
    fun canonical(): RichAttr = RichAttr(
        kind = kind,
        level = if (kind == RichKind.HEADING) level.coerceIn(1, 6) else 0,
        depth = if (isList) depth.coerceIn(0, MAX_DEPTH) else 0,
        checked = kind == RichKind.TASK && checked,
        number = if (kind == RichKind.ORDERED) number.coerceAtLeast(0) else 0,
    )

    companion object {
        const val MAX_DEPTH = 8
        val PARAGRAPH = RichAttr()
        fun heading(level: Int) = RichAttr(RichKind.HEADING, level = level)
    }
}

enum class RichStyle { BOLD, ITALIC, STRIKE, CODE, LINK }

/** One style over `[start, end)` of a block's text. [url] is a link's address, and empty otherwise. */
data class RichSpan(val start: Int, val end: Int, val style: RichStyle, val url: String = "")

/** One block: its attributes, its text (never holding a line break) and the styles over it. */
data class RichBlock(val attr: RichAttr = RichAttr.PARAGRAPH, val text: String = "", val spans: List<RichSpan> = emptyList()) {

    /**
     * The block as Markdown can hold it: the attributes canonical, the text on one line and
     * trimmed (a raw line is kept exactly), the spans clamped, merged and in order. A rule has
     * no text.
     */
    fun normalized(): RichBlock {
        val a = attr.canonical()
        if (a.kind == RichKind.RULE) return RichBlock(a)
        val oneLine = if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) text else text.replace('\n', ' ').replace('\r', ' ')
        if (a.kind == RichKind.RAW) return RichBlock(a, oneLine)
        val lead = oneLine.length - oneLine.trimStart().length
        val trimmed = oneLine.trim()
        val shifted = if (lead == 0) spans else spans.map { it.copy(start = it.start - lead, end = it.end - lead) }
        return RichBlock(a, trimmed, RichSpans.normalize(shifted, trimmed.length))
    }

    /** Nothing to write: Markdown cannot hold an empty paragraph, heading, quote or list item. */
    val isBlank: Boolean get() = attr.kind != RichKind.RULE && attr.kind != RichKind.RAW && text.isBlank()
}

object RichSpans {

    /**
     * Spans clamped to `[0, length]`, the empty ones dropped, those of one style (and, for
     * links, one address) that touch or overlap merged, in order of start then style.
     */
    fun normalize(spans: List<RichSpan>, length: Int): List<RichSpan> {
        if (spans.isEmpty()) return spans
        val out = ArrayList<RichSpan>(spans.size)
        val groups = spans
            .map { it.copy(start = it.start.coerceIn(0, length), end = it.end.coerceIn(0, length), url = if (it.style == RichStyle.LINK) it.url else "") }
            .filter { it.end > it.start }
            .groupBy { it.style to it.url }
        for ((_, group) in groups) {
            var current: RichSpan? = null
            for (span in group.sortedBy { it.start }) {
                val c = current
                current = when {
                    c == null -> span
                    span.start <= c.end -> c.copy(end = maxOf(c.end, span.end))
                    else -> { out += c; span }
                }
            }
            current?.let { out += it }
        }
        out.sortWith(compareBy<RichSpan> { it.start }.thenBy { it.style.ordinal }.thenBy { it.end }.thenBy { it.url })
        return out
    }
}

/** The whole document as Markdown can hold it: every block normalized, the blank ones dropped,
 *  and every ordered item carrying the number it shows. */
fun RichDoc.normalized(): RichDoc {
    val kept = blocks.map { it.normalized() }.filter { !it.isBlank }
    val numbers = RichRules.numbering(kept.map { it.attr })
    return RichDoc(kept.mapIndexed { i, b -> if (b.attr.kind == RichKind.ORDERED) b.copy(attr = b.attr.copy(number = numbers[i])) else b })
}
