package com.symmetricalpalmtree.soil.markdown.rich

/**
 * The rules a rendered editor edits blocks by, as pure functions: what a new line becomes, what
 * a format tool does to the blocks it touches, what Backspace at the start of a block undoes,
 * and how ordered items count. The view applies them; nothing here knows a view exists.
 */
object RichRules {

    /**
     * The number each block shows, 0 for all but ordered items. It is the Markdown parser's
     * count, exactly: the first ordered item of a run at a depth keeps the number it claims and
     * each one after it counts on; a bullet or task at that depth ends the run there, and any
     * block that is not a list item ends every run.
     */
    fun numbering(attrs: List<RichAttr>): IntArray {
        val out = IntArray(attrs.size)
        val counters = HashMap<Int, Int>()
        for ((i, a) in attrs.withIndex()) {
            when (a.kind) {
                RichKind.ORDERED -> {
                    val running = counters[a.depth]
                    val n = if (running != null) running + 1 else a.number
                    counters[a.depth] = n
                    out[i] = n
                }
                RichKind.BULLET, RichKind.TASK -> counters.remove(a.depth)
                else -> counters.clear()
            }
        }
        return out
    }

    /**
     * What the block after a line break is, given the block that was broken: a list goes on as
     * the same list (a task unticked), a quote and a raw line go on as themselves, and after
     * anything else comes a paragraph.
     */
    fun afterEnter(broken: RichAttr): RichAttr = when (broken.kind) {
        RichKind.BULLET, RichKind.ORDERED, RichKind.TASK -> broken.copy(checked = false)
        RichKind.QUOTE, RichKind.RAW -> broken
        else -> RichAttr.PARAGRAPH
    }

    /**
     * What an **empty** block becomes when Enter is pressed in it, or null to break the line as
     * usual: a nested list item moves out one level, and a list item or quote with nothing in it
     * ends its list or quote by becoming a paragraph.
     */
    fun enterOnEmpty(attr: RichAttr): RichAttr? = when {
        attr.isList && attr.depth > 0 -> attr.copy(depth = attr.depth - 1)
        attr.isList || attr.kind == RichKind.QUOTE -> RichAttr.PARAGRAPH
        else -> null
    }

    /**
     * What Backspace at the very start of a block does to it, or null to leave Backspace to join
     * the block to the one before: a nested list item moves out one level, and anything that is
     * not a plain paragraph becomes one.
     */
    fun backspaceAtStart(attr: RichAttr): RichAttr? = when {
        attr.isList && attr.depth > 0 -> attr.copy(depth = attr.depth - 1)
        attr.kind == RichKind.PARAGRAPH -> null
        else -> RichAttr.PARAGRAPH
    }

    /** A list item moved in or out by [delta] levels; anything else is left as it is. */
    fun indent(attr: RichAttr, delta: Int): RichAttr =
        if (attr.isList) attr.copy(depth = (attr.depth + delta).coerceIn(0, RichAttr.MAX_DEPTH)) else attr

    /**
     * A block tool pressed over [touched]: when every block is already what the tool makes, they
     * all go back to paragraphs; otherwise each becomes it. A list item keeps its depth when it
     * becomes another kind of list item, and a new ordered item claims 1.
     */
    fun retarget(touched: List<RichAttr>, kind: RichKind, level: Int = 0): List<RichAttr> {
        fun isIt(a: RichAttr) = a.kind == kind && (kind != RichKind.HEADING || a.level == level)
        if (kind == RichKind.PARAGRAPH || touched.all(::isIt)) return touched.map { RichAttr.PARAGRAPH }
        return touched.map { a ->
            if (isIt(a)) a else RichAttr(
                kind = kind,
                level = level,
                depth = if (a.isList) a.depth else 0,
                number = if (a.kind == RichKind.ORDERED) a.number else 1,
            ).canonical()
        }
    }

    /** Whether [next] sits tight under [previous], with no gap between: two list items, or two raw lines. */
    fun tight(previous: RichAttr, next: RichAttr): Boolean =
        (previous.isList && next.isList) || (previous.kind == RichKind.RAW && next.kind == RichKind.RAW)

    /**
     * The word around [caret] in [text] as `[start, end)`, or null when the caret touches no
     * word: what an inline tool acts on when nothing is selected.
     */
    fun wordAt(text: CharSequence, caret: Int): Pair<Int, Int>? {
        fun wordChar(c: Char) = c.isLetterOrDigit() || c == '\''
        var start = caret.coerceIn(0, text.length)
        var end = start
        while (start > 0 && wordChar(text[start - 1])) start--
        while (end < text.length && wordChar(text[end])) end++
        return if (end > start) start to end else null
    }
}
