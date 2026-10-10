package com.symmetricalpalmtree.soil.docsprout.editor.proofread

import com.symmetricalpalmtree.soil.markdown.MarkdownCode

/**
 * A candidate word for spell checking: [word] is `text.substring(start, end)`.
 *
 * Spans carry offsets into the *original* text so the editor can underline in place. Tokens are
 * inclusive — anything word-shaped is yielded, including words with digits or odd casing — and
 * [SpellEngine.shouldCheck] is the exclusive filter. Keeping the two roles apart means a word the
 * engine declines to judge ("2nd", "iPad", "café") stays one token instead of becoming shrapnel
 * like "nd".
 */
data class WordSpan(val start: Int, val end: Int, val word: String)

/**
 * Splits a document's Markdown into checkable word spans.
 *
 * The editor's text is Markdown, so a naive word split would spell-check code, URLs, and link
 * targets. The tokenizer skips everything that is not prose:
 *
 * - **Fenced code** (```` ``` ````/`~~~`, three or more) — every line from the opening fence to
 *   the closing one of the same character and at least its length ([MarkdownCode], the rendered
 *   editor's rule).
 * - **Indented lines** (4+ spaces or a tab at line start) — the whole line, code or not: wider
 *   than [MarkdownCode]'s indented code (which needs a blank line before it), as it always was.
 * - **Inline code** — a backtick run to the next run of the same length; unmatched backticks are
 *   literal text.
 * - **URLs** (`http://`, `https://`, `www.`) and **email addresses**.
 * - **Link/image targets** — the `(...)` of `[label](target)`; the label is still checked.
 * - **Links into the Bible** — the whole `[reference](bible:…)`: a reference is not prose.
 *
 * Inside prose, a word is a run of letters/digits with internal apostrophes (`'` or `’`), so
 * "don't" is one token — the bundled dictionary carries common contractions. Hyphens and
 * underscores separate ("e-ink" → "e", "ink"); a leading or trailing apostrophe is quotation, not
 * word ("'hello'" → "hello", and "gardeners'" → "gardeners", which the dictionary knows).
 */
object ProofreadTokenizer {

    private val INDENT = Regex("""^(?: {4,}|\t)""")
    private val URL = Regex("""\b(?:https?://|www\.)[^\s>)]+""")
    private val EMAIL = Regex("""\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val LINK_TARGET = Regex("""]\([^)]*\)""")
    private val BIBLE_LINK = Regex("""\[[^\]\n]*]\(bible:[^)]*\)""")

    /** Word spans of [text]'s prose, in document order. */
    fun wordSpans(text: String): List<WordSpan> =
        if (text.isEmpty()) emptyList() else collectWords(text, skipMask(text))

    /** Word spans of [text] against an already-computed [skip] mask — one mask, many consumers. */
    fun wordSpans(text: String, skip: BooleanArray): List<WordSpan> =
        if (text.isEmpty()) emptyList() else collectWords(text, skip)

    /** True at every index of [text] that is not prose — code, URLs, emails, link targets. */
    fun skipMask(text: String): BooleanArray {
        val skip = BooleanArray(text.length)
        if (text.isEmpty()) return skip
        markCodeLines(text, skip)
        markInlineCode(text, skip)
        markRegex(text, skip, URL)
        markRegex(text, skip, EMAIL)
        markLinkTargets(text, skip)
        // A link into the Bible is a reference, words and all: "Jn", "Cor" and a verse list are not prose to judge.
        markRegex(text, skip, BIBLE_LINK)
        return skip
    }

    /** Marks code lines — [MarkdownCode]'s, the rendered editor's own (a fence of either
     *  character and any length, closed only by its own kind) — and every indented line besides. */
    private fun markCodeLines(text: String, skip: BooleanArray) {
        val lines = text.split('\n')
        val code = MarkdownCode.codeLines(lines)
        var lineStart = 0
        for ((i, line) in lines.withIndex()) {
            val lineEnd = lineStart + line.length
            if (code[i] || INDENT.containsMatchIn(line)) skip.fill(true, lineStart, lineEnd)
            lineStart = lineEnd + 1
        }
    }

    /** Marks `code` spans: a backtick run to the next run of the same length, else literal. */
    private fun markInlineCode(text: String, skip: BooleanArray) {
        var i = 0
        while (i < text.length) {
            if (text[i] != '`' || skip[i]) { i++; continue }
            var runEnd = i
            while (runEnd < text.length && text[runEnd] == '`') runEnd++
            val runLen = runEnd - i
            val close = findBacktickRun(text, runEnd, runLen, skip)
            if (close < 0) { i = runEnd; continue }
            skip.fill(true, i, close + runLen)
            i = close + runLen
        }
    }

    /**
     * Index of the next backtick run of exactly [length] at or after [from], or -1. Masked indices
     * are invisible — a stray prose backtick must not pair with one inside an already-marked code
     * line and swallow the prose between them.
     */
    private fun findBacktickRun(text: String, from: Int, length: Int, skip: BooleanArray): Int {
        var i = from
        while (i < text.length) {
            if (skip[i] || text[i] != '`') { i++; continue }
            var end = i
            while (end < text.length && !skip[end] && text[end] == '`') end++
            if (end - i == length) return i
            i = end
        }
        return -1
    }

    private fun markRegex(text: String, skip: BooleanArray, regex: Regex) {
        for (m in regex.findAll(text)) skip.fill(true, m.range.first, m.range.last + 1)
    }

    /** Marks the `(...)` of `[label](target)` — the label stays checkable. */
    private fun markLinkTargets(text: String, skip: BooleanArray) {
        for (m in LINK_TARGET.findAll(text)) {
            // +1 keeps the `]` itself out of the skip; it is a separator either way.
            skip.fill(true, m.range.first + 1, m.range.last + 1)
        }
    }

    private fun collectWords(text: String, skip: BooleanArray): List<WordSpan> {
        val spans = mutableListOf<WordSpan>()
        var i = 0
        while (i < text.length) {
            if (skip[i] || !text[i].isLetterOrDigit()) { i++; continue }
            val start = i
            var end = i
            while (end < text.length && !skip[end]) {
                val c = text[end]
                if (c.isLetterOrDigit()) { end++; continue }
                // An apostrophe joins only when a letter/digit follows — internal, not quotation.
                val apostrophe = c == '\'' || c == '’'
                if (apostrophe && end + 1 < text.length && !skip[end + 1] &&
                    text[end + 1].isLetterOrDigit()
                ) { end += 2; continue }
                break
            }
            spans.add(WordSpan(start, end, text.substring(start, end)))
            i = end + 1
        }
        return spans
    }
}
