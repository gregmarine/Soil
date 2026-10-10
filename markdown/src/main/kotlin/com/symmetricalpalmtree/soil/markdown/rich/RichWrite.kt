package com.symmetricalpalmtree.soil.markdown.rich

/**
 * [RichDoc] → Markdown, in one canonical hand: `-` bullets, two spaces a level, `**bold**`,
 * `_italic_`, `~~strike~~`, a blank line between blocks and none between the items of a list.
 * What [RichParse] reads from the result is the document that was written
 * (`parse(write(doc)) == doc.normalized()`).
 *
 * Two rules keep that true:
 *
 *  - **Styles nest in one fixed order**: strikethrough outside bold outside italic, with code or
 *    a link innermost. Where styles overlap unevenly the runs are closed and reopened, so no
 *    marker ever closes across another.
 *  - **A plain character is escaped only where it would otherwise be read as markup**: a `*`,
 *    `_` or `` ` `` with its partner somewhere after it in the block, a `[` with a `](` after it, a `~` before another,
 *    a `!` before a link, every backslash, and whatever at the start of a paragraph would make it another kind of
 *    block. Text with none of that is written exactly as it reads.
 *
 * A raw line and an image are written exactly as they are held, so long as the line reads back
 * as raw where it stands: inside a fence, as a fence that is closed (or runs to the end, as an
 * unclosed one is read), as a table row, as a blank line, or as indented code after a blank
 * line. Any other raw line (one edited out of its shape) is written as a paragraph of its words,
 * escaped, so it reads back as those words and never as some other block. Pure Kotlin.
 */
object RichWrite {

    class Result(val text: String, /** Where each block of the document given starts in [text]. A block that is not written (it was blank) reports where the next one starts. */ val offsets: IntArray)

    fun write(doc: RichDoc): Result {
        val normal = doc.blocks.map { it.normalized() }
        val keptIndex = normal.indices.filter { !normal[it].isBlank }
        val kept = settleRaw(keptIndex.map { normal[it] })
        val numbers = RichRules.numbering(kept.map { it.attr })

        val out = StringBuilder()
        val offsets = IntArray(doc.blocks.size)
        var next = 0
        for ((k, block) in kept.withIndex()) {
            if (k > 0) out.append(if (RichRules.tight(kept[k - 1].attr, block.attr)) "\n" else "\n\n")
            while (next <= keptIndex[k]) offsets[next++] = out.length
            out.append(line(block, numbers[k]))
        }
        if (kept.isNotEmpty()) out.append('\n')
        while (next < offsets.size) offsets[next++] = out.length
        return Result(out.toString(), offsets)
    }

    /** The blocks with each raw line that would not read back as raw made a paragraph of its words. */
    private fun settleRaw(blocks: List<RichBlock>): List<RichBlock> {
        if (blocks.none { it.attr.kind == RichKind.RAW }) return blocks
        val raw = BooleanArray(blocks.size)
        fun isRaw(k: Int) = blocks[k].attr.kind == RichKind.RAW
        var indented = false
        // The kind the last block with words on it is written as, for what may follow a blank line.
        var lastSolid: RichAttr? = null
        var k = 0
        while (k < blocks.size) {
            val b = blocks[k]
            if (!isRaw(k)) { indented = false; lastSolid = b.attr; k++; continue }
            val t = b.text
            if (t.isBlank()) { raw[k] = true; k++; continue }
            // Indented code is read only after a blank line, which a raw line before it does not leave unless it is one.
            val afterBlank = k == 0 || !isRaw(k - 1) || !raw[k - 1] || blocks[k - 1].text.isBlank()
            if (RichParse.isIndentedCode(t) && (indented || (afterBlank && lastSolid?.isList != true))) {
                raw[k] = true; indented = true; lastSolid = b.attr; k++; continue
            }
            indented = false
            val fence = RichParse.fenceRun(t)
            if (fence != null) {
                var j = k + 1
                while (j < blocks.size && isRaw(j) && !RichParse.closesFence(blocks[j].text, fence)) j++
                val closed = j < blocks.size && isRaw(j)
                if (closed || j == blocks.size) {
                    val last = if (closed) j else j - 1
                    for (x in k..last) raw[x] = true
                    lastSolid = b.attr
                    k = last + 1
                    continue
                }
            } else if (t.trimStart().startsWith("|")) {
                raw[k] = true; lastSolid = b.attr; k++; continue
            }
            lastSolid = RichAttr.PARAGRAPH
            k++
        }
        return blocks.mapIndexed { i, b -> if (isRaw(i) && !raw[i]) RichBlock(RichAttr.PARAGRAPH, b.text).normalized() else b }
    }

    private fun line(block: RichBlock, number: Int): String {
        val a = block.attr
        val indent = "  ".repeat(a.depth)
        return when (a.kind) {
            RichKind.RULE -> "---"
            RichKind.RAW -> block.text
            RichKind.PARAGRAPH -> escapeBlockStart(inline(block))
            RichKind.HEADING -> "#".repeat(a.level) + " " + inline(block)
            RichKind.QUOTE -> "> " + inline(block)
            RichKind.BULLET -> marked(indent + "- ", inline(block))
            RichKind.TASK -> indent + (if (a.checked) "- [x] " else "- [ ] ") + inline(block)
            RichKind.ORDERED -> "$indent$number. " + inline(block)
        }
    }

    private val TASK_BOX = Regex("""^\[[xX ]\]\s""")

    /** A bullet whose words are only dashes would read, marker and all, as a rule; one that
     *  opens with a box would read as a task. */
    private fun marked(marker: String, content: String): String =
        if (RichParse.isHorizontalRule(marker + content) || TASK_BOX.containsMatchIn(content)) marker + "\\" + content else marker + content

    // ── Inlines ──────

    private sealed class Token {
        /** A marker, or anything written exactly as it is: code, a link, an image. */
        class Verbatim(val text: String) : Token()
        class Plain(val text: String) : Token()
    }

    private val NESTING = listOf(RichStyle.STRIKE to "~~", RichStyle.BOLD to "**", RichStyle.ITALIC to "_")

    private fun inline(block: RichBlock): String {
        val text = block.text
        if (text.isEmpty()) return ""
        val tokens = ArrayList<Token>()

        // The text in runs between which no style starts or stops.
        val cuts = sortedSetOf(0, text.length)
        for (s in block.spans) { cuts += s.start; cuts += s.end }
        val bounds = cuts.toList()
        var open = emptyList<Pair<RichStyle, String>>()
        for (b in 0 until bounds.size - 1) {
            val from = bounds[b]
            val to = bounds[b + 1]
            val here = block.spans.filter { it.start <= from && it.end >= to }
            val want = NESTING.filter { (style, _) -> here.any { it.style == style } }
            var common = 0
            while (common < open.size && common < want.size && open[common] == want[common]) common++
            for (k in open.size - 1 downTo common) tokens += Token.Verbatim(open[k].second)
            for (k in common until want.size) tokens += Token.Verbatim(want[k].second)
            open = want

            val piece = text.substring(from, to)
            val link = here.firstOrNull { it.style == RichStyle.LINK }
            when {
                link != null -> tokens += Token.Verbatim("[" + escapeLinkText(piece) + "](" + link.url.replace(")", "%29") + ")")
                // Code holds its characters as they are, so it cannot hold the one that ends it.
                here.any { it.style == RichStyle.CODE } && piece.indexOf('`') < 0 -> tokens += Token.Verbatim("`$piece`")
                else -> plain(piece, tokens)
            }
        }
        for (k in open.size - 1 downTo 0) tokens += Token.Verbatim(open[k].second)
        return escape(tokens)
    }

    /** Plain text, with each image in it set apart to be written as it is. */
    private fun plain(piece: String, tokens: MutableList<Token>) {
        var from = 0
        var i = piece.indexOf('!')
        while (i >= 0) {
            val end = RichInline.imageEnd(piece, i)
            if (end > 0) {
                if (i > from) tokens += Token.Plain(piece.substring(from, i))
                tokens += Token.Verbatim(piece.substring(i, end))
                from = end
                i = piece.indexOf('!', end)
            } else {
                i = piece.indexOf('!', i + 1)
            }
        }
        if (from < piece.length) tokens += Token.Plain(piece.substring(from))
    }

    /**
     * The tokens joined, right to left, so each plain character knows what follows it: it is
     * escaped only when its partner stands somewhere after it.
     */
    private fun escape(tokens: List<Token>): String {
        val parts = ArrayList<String>(tokens.size)
        var star = false
        var underscore = false
        var backtick = false
        var closeBracket = false
        var following = ' '
        for (t in tokens.indices.reversed()) {
            when (val token = tokens[t]) {
                is Token.Verbatim -> {
                    val s = token.text
                    if (s.indexOf('*') >= 0) star = true
                    if (s.indexOf('_') >= 0) underscore = true
                    if (s.indexOf('`') >= 0) backtick = true
                    if (s.indexOf("](") >= 0) closeBracket = true
                    if (s.isNotEmpty()) following = s[0]
                    parts += s
                }
                is Token.Plain -> {
                    val sb = StringBuilder(token.text.length + 8)
                    for (i in token.text.indices.reversed()) {
                        val c = token.text[i]
                        val escaped = when (c) {
                            '\\' -> true
                            '*' -> star.also { star = true }
                            '_' -> underscore.also { underscore = true }
                            '`' -> backtick.also { backtick = true }
                            '[' -> closeBracket
                            // Only a `]` with a `(` straight after it can end a link's text.
                            ']' -> { if (following == '(') closeBracket = true; false }
                            '~' -> following == '~'
                            // Or the link after it would be read as an image.
                            '!' -> following == '['
                            else -> false
                        }
                        sb.append(c)
                        if (escaped) sb.append('\\')
                        following = if (escaped) '\\' else c
                    }
                    parts += sb.reverse().toString()
                }
            }
        }
        return parts.asReversed().joinToString("")
    }

    private fun escapeLinkText(text: String): String {
        if (text.none { it == '\\' || it == '[' || it == ']' }) return text
        val sb = StringBuilder(text.length + 4)
        for (c in text) {
            if (c == '\\' || c == '[' || c == ']') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private val HEADING_START = Regex("""^#{1,6}\s""")
    private val BULLET_START = Regex("""^[-*+]\s""")
    private val ORDERED_START = Regex("""^(\d+)\.\s""")

    /** A paragraph whose first characters would make it another kind of block has them escaped. */
    private fun escapeBlockStart(line: String): String = when {
        line.isEmpty() -> line
        HEADING_START.containsMatchIn(line) || BULLET_START.containsMatchIn(line) -> "\\" + line
        line[0] == '>' || line[0] == '|' -> "\\" + line
        // Only a strike's marker and the tilde inside it can start a line with three: escape that one, or it is a fence.
        line.startsWith("~~~") -> line.substring(0, 2) + "\\" + line.substring(2)
        RichParse.isHorizontalRule(line) -> "\\" + line
        else -> ORDERED_START.find(line)?.let { m -> line.substring(0, m.groupValues[1].length) + "\\" + line.substring(m.groupValues[1].length) } ?: line
    }
}
