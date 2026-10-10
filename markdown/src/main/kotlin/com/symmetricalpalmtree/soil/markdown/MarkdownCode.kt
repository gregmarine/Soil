package com.symmetricalpalmtree.soil.markdown

/**
 * Which lines of a Markdown text are code — kept as written, never read as Markdown. The one
 * rule every reader of a document's source shares: the rendered editor's [rich.RichParse], the
 * list renumbering, the reflow, and the proofread's skip mask. The notebook's [MarkdownParser]
 * deliberately stays without it: text boxes were measured under its reading.
 *
 *  - **A fence** is three or more backticks or tildes at the start of a line (after any indent),
 *    closed by a line of the same character at least as long and nothing else. The words after
 *    the opening run may not hold its character (else the line is not a fence). A fence never
 *    closed runs to the end of the text. Its opening and closing lines are code too.
 *  - **Indented code** is lines indented four columns or more (a tab is four) after a blank line,
 *    when the block before is not a list item. A line that reads as a list item is one, at its
 *    depth, and never code: that is how a nested item is written. Blank lines inside it are its
 *    own only when more of it follows them.
 *
 * Pure Kotlin; every pattern is anchored to one line.
 */
object MarkdownCode {

    private val TASK_ITEM = Regex("""^[-*+]\s+\[([xX ])\]\s+(.*)""")
    private val UNORDERED_ITEM = Regex("""^[-*+]\s+(.+)""")
    private val ORDERED_ITEM = Regex("""^(\d+)\.\s+(.+)""")

    /** The run of three or more backticks or tildes that opens a fence on [line], or null. The
     *  words after the run (the info string) may not hold the run's character: `~~~30~~ 25` is a
     *  strike an older writer left unescaped, and ```` ```code``` ```` is inline code, never a fence. */
    fun fenceRun(line: String): String? {
        val t = line.trimStart()
        if (t.isEmpty() || (t[0] != '`' && t[0] != '~')) return null
        var n = 0
        while (n < t.length && t[n] == t[0]) n++
        if (n < 3 || t.indexOf(t[0], n) >= 0) return null
        return t.substring(0, n)
    }

    /** Whether [line] closes the fence opened by [run]: the same character, at least as many, and nothing else. */
    fun closesFence(line: String, run: String): Boolean {
        val t = line.trim()
        return t.length >= run.length && t.all { it == run[0] }
    }

    /** A line indented four columns or more (a tab is four), with something on it that is not a list item. */
    fun isIndentedCode(line: String): Boolean {
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
        return t.isNotEmpty() && !isListItem(t)
    }

    /** Whether the trimmed [line] reads as a list item (task, bullet or number). A rule such as
     *  `- - -` matches too: callers that care ask about the rule first. */
    internal fun isListItem(line: String): Boolean =
        TASK_ITEM.find(line)?.range?.first == 0 ||
            UNORDERED_ITEM.find(line)?.range?.first == 0 ||
            ORDERED_ITEM.find(line)?.range?.first == 0

    /** Three or more of `-`, `*`, or `_`; mixed together they are not a rule. */
    internal fun isHorizontalRule(line: String): Boolean {
        val bare = line.replace(" ", "").replace("\t", "")
        if (bare.length < 3) return false
        return bare.all { it == '-' } || bare.all { it == '*' } || bare.all { it == '_' }
    }

    /** For each of [lines] (already split, no line breaks), whether it is code by the rules above. */
    fun codeLines(lines: List<String>): BooleanArray {
        val code = BooleanArray(lines.size)
        // Whether the block before is a list item: an indented line under one is the list's.
        var afterList = false
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) { i++; continue }

            if (isIndentedCode(raw) && (i == 0 || lines[i - 1].isBlank()) && !afterList) {
                code[i] = true
                i++
                while (i < lines.size) {
                    if (isIndentedCode(lines[i])) { code[i] = true; i++; continue }
                    if (!lines[i].isBlank()) break
                    var next = i
                    while (next < lines.size && lines[next].isBlank()) next++
                    if (next >= lines.size || !isIndentedCode(lines[next])) break
                    while (i < next) { code[i] = true; i++ }
                }
                afterList = false
                continue
            }

            val fence = fenceRun(raw.trim())
            if (fence != null) {
                code[i] = true
                i++
                while (i < lines.size) {
                    code[i] = true
                    val closes = closesFence(lines[i], fence)
                    i++
                    if (closes) break
                }
                afterList = false
                continue
            }

            // Any other line starts or continues a block; only a list item's own line leaves a
            // list as the block before (a list item never takes the next line onto it).
            val t = raw.trim()
            afterList = !t.startsWith("|") && !isHorizontalRule(t) && isListItem(t)
            i++
        }
        return code
    }
}
