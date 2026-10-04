package com.symmetricalpalmtree.soil.markdown.rich

/**
 * **Markdown typed into a rendered document becomes what it means**, at the moment the typing
 * completes it: a marker and its space at the start of a block, or the closing marker of a pair
 * around some words. Both are decided here from the block's words up to the caret, and nothing
 * else; the editor takes the markers out and applies the answer.
 *
 * Only a completed shape converts, so half-typed markup stays as typed, and what would be a
 * false alarm in prose (`2 * 3 * 4`, `snake_case_word`) is left alone: a pair converts only
 * around words that start and end with a character that is not a space, and an underscore only
 * opens a pair at the start of a word.
 */
object RichTyping {

    /** The first [length] characters of the block are a marker: take them out, and the block is [attr]. */
    class LineStart(val length: Int, val attr: RichAttr)

    private val HEADING = Regex("""^(#{1,6}) $""")
    private val BULLET = Regex("""^[-*+] $""")
    private val ORDERED = Regex("""^(\d{1,9})\. $""")
    private val TASK = Regex("""^\[([xX ])\] $""")

    /**
     * [typed] is the block's words from its start to the caret, a space having just been typed.
     * A paragraph that so far is only a marker becomes that kind of block; a bullet that so far
     * is only a box becomes a task.
     */
    fun lineStart(typed: String, attr: RichAttr): LineStart? {
        if (attr.kind == RichKind.BULLET) {
            val box = TASK.find(typed) ?: return null
            return LineStart(typed.length, attr.copy(kind = RichKind.TASK, checked = box.groupValues[1] != " "))
        }
        if (attr.kind != RichKind.PARAGRAPH) return null
        HEADING.find(typed)?.let { return LineStart(typed.length, RichAttr.heading(it.groupValues[1].length)) }
        if (BULLET.matches(typed)) return LineStart(typed.length, RichAttr(RichKind.BULLET))
        ORDERED.find(typed)?.let { return LineStart(typed.length, RichAttr(RichKind.ORDERED, number = it.groupValues[1].toInt())) }
        if (typed == "> ") return LineStart(typed.length, RichAttr(RichKind.QUOTE))
        return null
    }

    /**
     * A pair closed at the caret: its opening marker starts at [openStart], both markers are
     * [markerLength] long, and the words between them take [style].
     */
    class Pair(val openStart: Int, val markerLength: Int, val style: RichStyle)

    /** [typed] is the block's words from its start to the caret, a marker character having just been typed. */
    fun pairClosed(typed: String): Pair? {
        if (typed.isEmpty()) return null
        return when (typed.last()) {
            '*' -> if (typed.endsWith("**")) double(typed, "**", RichStyle.BOLD) else single(typed, '*')
            '_' -> if (typed.endsWith("__")) double(typed, "__", RichStyle.BOLD) else single(typed, '_')
            '~' -> if (typed.endsWith("~~")) double(typed, "~~", RichStyle.STRIKE) else null
            '`' -> {
                val open = typed.lastIndexOf('`', typed.length - 2)
                if (open >= 0 && typed.substring(open + 1, typed.length - 1).isNotBlank()) Pair(open, 1, RichStyle.CODE) else null
            }
            else -> null
        }
    }

    private fun double(typed: String, marker: String, style: RichStyle): Pair? {
        val c = marker[0]
        // A third marker character either side is some other shape: leave it as typed.
        if (typed.length >= 3 && typed[typed.length - 3] == c) return null
        val open = typed.lastIndexOf(marker, typed.length - 5)
        if (open < 0 || (open > 0 && typed[open - 1] == c)) return null
        return if (wraps(typed, open + 2, typed.length - 2, c)) Pair(open, 2, style) else null
    }

    private fun single(typed: String, c: Char): Pair? {
        val open = typed.lastIndexOf(c, typed.length - 2)
        if (open < 0) return null
        // A marker beside another of its kind is half of a double one.
        if ((open > 0 && typed[open - 1] == c) || typed[open + 1] == c) return null
        // An underscore inside a word is part of the word.
        if (c == '_' && open > 0 && typed[open - 1].isLetterOrDigit()) return null
        return if (wraps(typed, open + 1, typed.length - 1, c)) Pair(open, 1, RichStyle.ITALIC) else null
    }

    /** `[from, to)` is something to style: not empty, not spaced at either end, and holding no marker character of its own. */
    private fun wraps(typed: String, from: Int, to: Int, c: Char): Boolean =
        to > from && !typed[from].isWhitespace() && !typed[to - 1].isWhitespace() && typed.indexOf(c, from).let { it < 0 || it >= to }
}
