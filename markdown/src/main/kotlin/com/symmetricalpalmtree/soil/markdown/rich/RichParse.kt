package com.symmetricalpalmtree.soil.markdown.rich

/**
 * Markdown → [RichDoc]. The block grammar is [com.symmetricalpalmtree.soil.markdown.MarkdownParser]'s,
 * line for line, with three things that parser does not need and a rendered editor does:
 *
 *  - **Backslash escapes.** `\` before a punctuation character makes it a plain character, so
 *    what [RichWrite] escaped reads back as it was typed.
 *  - **Raw lines.** A fenced block, an indented code block and a table row are kept exactly as
 *    written, a line a block. A fence is three or more backticks or tildes, closed by a line of
 *    the same character at least as long and nothing else; a fence never closed runs to the end
 *    of the document. An indented code block is lines indented four spaces (or a tab) after a
 *    blank line, when the block before it is not a list item; a line that reads as a list item
 *    is one, at its depth, and never code, since that is how a nested item is written.
 *  - **Images stay as written**: the characters `![alt](url)`, untouched.
 *
 * Every block also reports where it began in the source, so a cursor can be carried between the
 * source and the rendered text by block.
 *
 * Pure Kotlin. Block detection is line by line and every pattern is anchored to one line.
 */
object RichParse {

    class Result(val doc: RichDoc, /** Where each block's first line starts in the source. */ val offsets: IntArray)

    private val HEADING = Regex("""^(#{1,6})\s+(.+)""")
    private val BLOCKQUOTE = Regex("""^>\s?(.*)""")
    private val TASK_ITEM = Regex("""^[-*+]\s+\[([xX ])\]\s+(.*)""")
    private val UNORDERED_ITEM = Regex("""^[-*+]\s+(.+)""")
    private val ORDERED_ITEM = Regex("""^(\d+)\.\s+(.+)""")

    fun parse(markdown: String): Result {
        val lines = ArrayList<String>()
        val starts = ArrayList<Int>()
        var at = 0
        while (true) {
            val nl = markdown.indexOf('\n', at)
            val end = if (nl < 0) markdown.length else nl
            lines += markdown.substring(at, end).removeSuffix("\r")
            starts += at
            if (nl < 0) break
            at = nl + 1
        }

        val blocks = ArrayList<RichBlock>()
        val offsets = ArrayList<Int>()
        fun add(block: RichBlock, line: Int) { blocks += block; offsets += starts[line] }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val leftTrimmed = raw.trimStart()
            val depth = ((raw.length - leftTrimmed.length) / 2).coerceAtMost(RichAttr.MAX_DEPTH)
            val line = leftTrimmed.trimEnd()

            if (line.isEmpty()) {
                // A blank line between two raw lines is theirs: without it two tables would
                // come back as one.
                if (blocks.lastOrNull()?.attr?.kind == RichKind.RAW) {
                    var next = i + 1
                    while (next < lines.size && lines[next].isBlank()) next++
                    if (next < lines.size && (isRawStart(lines[next].trim()) || isIndentedCode(lines[next]))) add(RichBlock(RAW, ""), i)
                }
                i++
                continue
            }

            // Indented code: only after a blank line (it cannot interrupt a paragraph), and not
            // under a list item, where the indent is the list's.
            if (isIndentedCode(raw) && (i == 0 || lines[i - 1].isBlank()) && blocks.lastOrNull()?.attr?.isList != true) {
                add(RichBlock(RAW, raw), i)
                i++
                while (i < lines.size) {
                    if (isIndentedCode(lines[i])) { add(RichBlock(RAW, lines[i]), i); i++; continue }
                    if (!lines[i].isBlank()) break
                    // Blank lines are the block's only when more of it follows them.
                    var next = i
                    while (next < lines.size && lines[next].isBlank()) next++
                    if (next >= lines.size || !isIndentedCode(lines[next])) break
                    while (i < next) { add(RichBlock(RAW, lines[i]), i); i++ }
                }
                continue
            }

            val fence = fenceRun(line)
            if (fence != null) {
                add(RichBlock(RAW, raw), i)
                i++
                while (i < lines.size) {
                    add(RichBlock(RAW, lines[i]), i)
                    val closes = closesFence(lines[i], fence)
                    i++
                    if (closes) break
                }
                continue
            }

            if (line.startsWith("|")) {
                add(RichBlock(RAW, raw), i)
                i++
                continue
            }

            val heading = HEADING.matchAt0(line)
            if (heading != null) {
                add(inline(RichAttr.heading(heading.groupValues[1].length), heading.groupValues[2].trim()), i)
                i++
                continue
            }

            // Checked before lists: `- - -` and `***` are rules, not bullets.
            if (isHorizontalRule(line)) {
                add(RichBlock(RichAttr(RichKind.RULE)), i)
                i++
                continue
            }

            val quote = BLOCKQUOTE.matchAt0(line)
            if (quote != null) {
                val first = i
                val quoted = mutableListOf(quote.groupValues[1])
                while (i + 1 < lines.size) {
                    val next = BLOCKQUOTE.matchAt0(lines[i + 1].trim()) ?: break
                    quoted += next.groupValues[1]
                    i++
                }
                add(inline(RichAttr(RichKind.QUOTE), quoted.joinToString(" ").trim()), first)
                i++
                continue
            }

            // Tasks before plain bullets: `- [x] done` is also a bullet.
            val task = TASK_ITEM.matchAt0(line)
            if (task != null) {
                add(inline(RichAttr(RichKind.TASK, depth = depth, checked = task.groupValues[1].lowercase() == "x"), task.groupValues[2]), i)
                i++
                continue
            }

            val bullet = UNORDERED_ITEM.matchAt0(line)
            if (bullet != null) {
                add(inline(RichAttr(RichKind.BULLET, depth = depth), bullet.groupValues[1]), i)
                i++
                continue
            }

            val numbered = ORDERED_ITEM.matchAt0(line)
            if (numbered != null) {
                add(inline(RichAttr(RichKind.ORDERED, depth = depth, number = numbered.groupValues[1].toIntOrNull() ?: 1), numbered.groupValues[2]), i)
                i++
                continue
            }

            // Paragraph: following lines join it until a blank one or the start of another block.
            val first = i
            val paragraph = mutableListOf(line)
            while (i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                if (next.isEmpty() || startsBlock(next)) break
                paragraph += next
                i++
            }
            add(inline(RichAttr.PARAGRAPH, paragraph.joinToString(" ")), first)
            i++
        }

        // An ordered item shows the parser's count, not the number it claims.
        val numbers = RichRules.numbering(blocks.map { it.attr })
        for (b in blocks.indices) {
            if (blocks[b].attr.kind == RichKind.ORDERED) blocks[b] = blocks[b].copy(attr = blocks[b].attr.copy(number = numbers[b]))
        }
        return Result(RichDoc(blocks), offsets.toIntArray())
    }

    private val RAW = RichAttr(RichKind.RAW)

    private fun Regex.matchAt0(line: String): MatchResult? = find(line)?.takeIf { it.range.first == 0 }

    private fun isRawStart(line: String): Boolean = fenceRun(line) != null || line.startsWith("|")

    /** The run of three or more backticks or tildes that opens a fence on [line], or null. */
    internal fun fenceRun(line: String): String? {
        val t = line.trimStart()
        if (t.isEmpty() || (t[0] != '`' && t[0] != '~')) return null
        var n = 0
        while (n < t.length && t[n] == t[0]) n++
        return if (n >= 3) t.substring(0, n) else null
    }

    /** Whether [line] closes the fence opened by [run]: the same character, at least as many, and nothing else. */
    internal fun closesFence(line: String, run: String): Boolean {
        val t = line.trim()
        return t.length >= run.length && t.all { it == run[0] }
    }

    /** A line indented four columns or more (a tab is four), with something on it that is not a list item. */
    internal fun isIndentedCode(line: String): Boolean {
        var col = 0
        for (c in line) {
            when (c) {
                ' ' -> col++
                '\t' -> col += 4 - col % 4
                else -> break
            }
        }
        if (col < 4) return false
        val t = line.trim()
        return t.isNotEmpty() && TASK_ITEM.matchAt0(t) == null && UNORDERED_ITEM.matchAt0(t) == null && ORDERED_ITEM.matchAt0(t) == null
    }

    private fun startsBlock(line: String): Boolean =
        isRawStart(line) ||
            HEADING.matchAt0(line) != null ||
            isHorizontalRule(line) ||
            BLOCKQUOTE.matchAt0(line) != null ||
            TASK_ITEM.matchAt0(line) != null ||
            UNORDERED_ITEM.matchAt0(line) != null ||
            ORDERED_ITEM.matchAt0(line) != null

    /** Three or more of `-`, `*`, or `_`; mixed together they are not a rule. */
    internal fun isHorizontalRule(line: String): Boolean {
        val bare = line.replace(" ", "").replace("\t", "")
        if (bare.length < 3) return false
        return bare.all { it == '-' } || bare.all { it == '*' } || bare.all { it == '_' }
    }

    // ── Inlines ──────

    private fun inline(attr: RichAttr, source: String): RichBlock {
        val text = StringBuilder()
        val spans = ArrayList<RichSpan>()
        inlineInto(source, text, spans)
        return RichBlock(attr, text.toString(), RichSpans.normalize(spans, text.length))
    }

    /**
     * An index scan, never a regex: the markers nest, and half-typed markup must fall through to
     * the characters as typed. Code wins outright; two-character markers are tried before their
     * one-character selves; an image is kept whole before a link can claim its brackets.
     * Markup with nothing inside it is left as its characters, so nothing typed is ever lost.
     */
    private fun inlineInto(src: String, out: StringBuilder, spans: MutableList<RichSpan>) {
        val scan = RichInline.Scan(src)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < src.length && RichInline.isPunctuation(src[i + 1]) -> {
                    out.append(src[i + 1])
                    i += 2
                }

                c == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i + 1) {
                        val start = out.length
                        out.append(src, i + 1, end)
                        spans += RichSpan(start, out.length, RichStyle.CODE)
                        i = end + 1
                    } else { out.append(c); i++ }
                }

                src.startsWith("~~", i) -> i = styled(src, i, "~~", RichStyle.STRIKE, out, spans, scan)
                src.startsWith("**", i) -> i = styled(src, i, "**", RichStyle.BOLD, out, spans, scan)
                src.startsWith("__", i) -> i = styled(src, i, "__", RichStyle.BOLD, out, spans, scan)

                c == '!' && RichInline.imageEnd(src, i) > 0 -> {
                    val end = RichInline.imageEnd(src, i)
                    out.append(src, i, end)
                    i = end
                }

                c == '[' -> {
                    val link = RichInline.linkAt(src, i, scan)
                    val display = link?.let { RichInline.unescape(src.substring(i + 1, it.textEnd)) }
                    if (link != null && !display.isNullOrEmpty()) {
                        val start = out.length
                        out.append(display)
                        spans += RichSpan(start, out.length, RichStyle.LINK, src.substring(link.textEnd + 2, link.end - 1))
                        i = link.end
                    } else { out.append(c); i++ }
                }

                c == '*' -> i = styled(src, i, "*", RichStyle.ITALIC, out, spans, scan)
                c == '_' -> i = styled(src, i, "_", RichStyle.ITALIC, out, spans, scan)

                else -> { out.append(c); i++ }
            }
        }
    }

    /** The run opened by [marker] at [i], when it closes around something; else the marker's
     *  first character as itself. Answers where the scan goes on. */
    private fun styled(src: String, i: Int, marker: String, style: RichStyle, out: StringBuilder, spans: MutableList<RichSpan>, scan: RichInline.Scan): Int {
        val close = RichInline.closer(src, i + marker.length, marker, scan)
        if (close <= i + marker.length) {
            out.append(src[i])
            return i + 1
        }
        val start = out.length
        inlineInto(src.substring(i + marker.length, close), out, spans)
        if (out.length > start) spans += RichSpan(start, out.length, style)
        return close + marker.length
    }
}

/** What the reader and the writer of inline Markdown must agree on, kept in one place. */
internal object RichInline {

    fun isPunctuation(c: Char): Boolean = c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'

    /** Where the image that starts at `src[i] == '!'` ends (exclusive), or -1 when there is none. */
    fun imageEnd(src: String, i: Int): Int {
        if (i + 1 >= src.length || src[i] != '!' || src[i + 1] != '[') return -1
        val altEnd = src.indexOf(']', i + 2)
        if (altEnd < 0 || altEnd + 1 >= src.length || src[altEnd + 1] != '(') return -1
        val urlEnd = src.indexOf(')', altEnd + 2)
        return if (urlEnd < 0) -1 else urlEnd + 1
    }

    class Link(val textEnd: Int, val end: Int)

    /**
     * Where the next unescaped `]` and the next `)` stand from each position of one string,
     * worked out once, so a line of many `[` is not scanned to its end for each of them. Escapes
     * are read left to right from the start; every position a scan begins at is a boundary of
     * that reading, so the answer is the one a scan from there would find.
     */
    class Scan(src: String) {
        private val close: IntArray
        private val paren: IntArray

        init {
            val n = src.length
            val escaped = BooleanArray(n)
            var j = 0
            while (j < n) {
                if (src[j] == '\\' && j + 1 < n && isPunctuation(src[j + 1])) { escaped[j + 1] = true; j += 2 } else j++
            }
            close = IntArray(n + 1)
            paren = IntArray(n + 1)
            close[n] = -1
            paren[n] = -1
            for (k in n - 1 downTo 0) {
                close[k] = if (src[k] == ']' && !escaped[k]) k else close[k + 1]
                paren[k] = if (src[k] == ')') k else paren[k + 1]
            }
        }

        fun closeFrom(from: Int): Int = if (from >= close.size) -1 else close[from]
        fun parenFrom(from: Int): Int = if (from >= paren.size) -1 else paren[from]
    }

    /** The link that starts at `src[i] == '['`: where its text's `]` is and where it ends
     *  (exclusive), or null. The text's own brackets are escaped, so a `\]` does not close it.
     *  [scan], when given, is [src]'s, and saves scanning it again. */
    fun linkAt(src: String, i: Int, scan: Scan? = null): Link? {
        val j: Int
        if (scan != null) {
            j = scan.closeFrom(i + 1)
            if (j < 0) return null
        } else {
            var k = i + 1
            while (k < src.length) {
                val c = src[k]
                if (c == '\\' && k + 1 < src.length && isPunctuation(src[k + 1])) { k += 2; continue }
                if (c == ']') break
                k++
            }
            j = k
        }
        if (j >= src.length || j + 1 >= src.length || src[j + 1] != '(') return null
        val urlEnd = scan?.parenFrom(j + 2) ?: src.indexOf(')', j + 2)
        return if (urlEnd < 0) null else Link(j, urlEnd + 1)
    }

    /**
     * Where [marker] next stands from [from], or -1: never an escaped character, never inside
     * code, a link or an image, each of which is stepped over whole.
     */
    fun closer(src: String, from: Int, marker: String, scan: Scan? = null): Int {
        var j = from
        while (j < src.length) {
            val c = src[j]
            when {
                c == '\\' && j + 1 < src.length && isPunctuation(src[j + 1]) -> j += 2
                src.startsWith(marker, j) -> return j
                c == '`' -> {
                    val end = src.indexOf('`', j + 1)
                    j = if (end > j + 1) end + 1 else j + 1
                }
                c == '!' && imageEnd(src, j) > 0 -> j = imageEnd(src, j)
                c == '[' -> j = linkAt(src, j, scan)?.end ?: (j + 1)
                else -> j++
            }
        }
        return -1
    }

    fun unescape(text: String): String {
        if (text.indexOf('\\') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && isPunctuation(text[i + 1])) { out.append(text[i + 1]); i += 2 }
            else { out.append(text[i]); i++ }
        }
        return out.toString()
    }
}
