package com.symmetricalpalmtree.soil.bibleref

/**
 * **References found in prose.** What a document's own pass runs over its words: every stretch
 * of text that reads as a Bible reference, by [ReferenceParser]'s grammar, whole chapters
 * included (`Genesis 1`, `Acts 2–3`), with where it starts and ends so the words can be wrapped
 * in a link as they were written. Pure; never throws.
 *
 * A book is a name, a USFM code or an alias [Canon.lookup] knows, written with a capital
 * (`John`, `Ps.`, `1 Cor`, `I Corinthians`, `Song of Solomon`, `JHN`), on a word boundary. An
 * alias of two letters needs its period (`Ps. 23`, never `is 3:1` in "the ratio is 3:1"). The
 * spec that follows is the parser's: chapters, verses, dashes of any kind, and `,` lists that
 * carry the chapter forward (`John 3:14-16, 18`). A list is cut back from its end until what is
 * left parses, so "John 3:16, 2019" links `John 3:16`; a trailing comma is never part of a hit.
 * The chapters must exist ([Canon.chapterCount]); the verses are bounded by the parser alone.
 *
 * A reference lies on one line: no space in it crosses a line break, so a list's
 * "1. Genesis" never reaches the next item's number.
 *
 * Two references in one sentence are two hits, each its own link: `; Acts 1:3` starts over at
 * the book name. What is inside an existing link, a code span or anything else is the caller's
 * to mask: the scanner reads the text it is given.
 */
object ReferenceScan {

    data class Hit(val start: Int, val end: Int, val passages: List<Passage>) {
        val wire: String get() = ReferenceCodec.encode(passages)
    }

    /** Space within one line: a reference never runs across a line break ("1. Genesis\n2. Exodus"). */
    private const val SP = "[^\\S\\r\\n]"
    private const val PREFIX = "(?:(?:[123]|III|II|I|1st|2nd|3rd|First|Second|Third)$SP*)?"
    private const val WORD = "[A-Z][A-Za-z]*(?:$SP+of$SP+[A-Z][a-z]+)?"
    private val book = Regex("(?<![A-Za-z0-9])($PREFIX$WORD)(\\.?)$SP*(?=[0-9])")

    private const val NUMBER = "[0-9]+(?::[0-9]+)?"
    private val firstSegment = Regex("^$NUMBER(?:$SP*[-–—]$SP*$NUMBER)?")
    private val nextSegment = Regex("^$SP*,$SP*($NUMBER(?:$SP*[-–—]$SP*$NUMBER)?)")

    /** Every reference in [text], in order, none overlapping. */
    fun scan(text: String): List<Hit> {
        if (text.length < 3) return emptyList()
        val hits = ArrayList<Hit>()
        var from = 0
        while (from < text.length) {
            val m = book.find(text, from) ?: break
            val hit = hitAt(text, m)
            if (hit == null) { from = m.range.first + 1; continue }
            hits += hit
            from = hit.end
        }
        return hits
    }

    private fun hitAt(text: String, m: MatchResult): Hit? {
        val name = m.groupValues[1]
        val dotted = m.groupValues[2].isNotEmpty()
        val bookEntry = Canon.lookup(name) ?: return null
        if (needsPeriod(name, bookEntry) && !dotted) return null
        val specStart = m.range.last + 1
        val ends = segmentEnds(text, specStart)
        if (ends.isEmpty()) return null
        for (k in ends.indices.reversed()) {
            val end = ends[k]
            val words = text.substring(m.range.first, end)
            if (words.any { it == '\n' || it == '\r' }) continue
            val parsed = ReferenceParser.parse(words) ?: continue
            val passages = ReferenceResolver.normalize(listOf(parsed), Canon::chapterCount)
            if (!ReferenceResolver.valid(passages, Canon::chapterCount) { true }) continue
            return Hit(m.range.first, end, passages)
        }
        return null
    }

    /** The end offset of each `,`-separated segment of the spec at [at], in order. */
    private fun segmentEnds(text: String, at: Int): List<Int> {
        val first = firstSegment.find(text.substring(at)) ?: return emptyList()
        val ends = arrayListOf(at + first.range.last + 1)
        while (true) {
            val next = nextSegment.find(text.substring(ends.last())) ?: break
            ends += ends.last() + next.range.last + 1
        }
        return ends
    }

    /** A two-letter alias reads as a word too often ("is", "am", "he", "ex") unless it is dotted. */
    private fun needsPeriod(written: String, book: CanonBook): Boolean {
        val bare = written.replace(Regex("[\\s.]"), "")
        if (bare.length > 2) return false
        return !bare.equals(book.name, ignoreCase = true) && !bare.equals(book.usfm, ignoreCase = true)
    }
}
