package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.bibleref.*

/**
 * The verses of a small passage as the Markdown a notebook text object holds (an earlier arc "Verses")
 * — the pure half of `IBible.passageText`, JVM-tested over fake [VerseRow]s with no database.
 *
 * The user's shape (2026-09-13): a **bold label line** — the canonical form the reference
 * resolves to (`**John 3:16–18**`) — then the verses as prose with **plain verse numbers**
 * (`16 For God so loved…`), one paragraph per chapter run, exactly where [PassageAtoms] puts a
 * heading: a passage read across a chapter edge changes paragraph there, and a passage across
 * books says so with a second label line of its own.
 *
 * The page's cap on what may be placed (ten verses, never a whole chapter) is `VerseCap` in
 * `:bible-ref`, applied by the notebook before it asks; this service answers up to a chapter.
 *
 * **Never logged**: what this builds is scripture, and where it comes from is a reference.
 */
object PassageMarkdown {

    /**
     * [verses] (in reading order, the ranges concatenated as written, the rows the source has)
     * under [label] as Markdown. Every (book, chapter) run is one paragraph; the first opens
     * under the bold label, a later one under a bold label of its own book and chapter so the
     * reader never mistakes where a paragraph came from. Empty for no verses.
     */
    fun build(label: String, verses: List<VerseRow>): String {
        if (verses.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("**").append(label.trim()).append("**")
        var usfm: String? = null
        var chapter = 0
        var first = true
        for (row in verses) {
            if (row.usfm != usfm || row.chapter != chapter) {
                usfm = row.usfm
                chapter = row.chapter
                if (!first) {
                    sb.append("\n\n**").append(Canon.chapterTitleName(row.usfm)).append(' ').append(row.chapter).append("**")
                }
                sb.append("\n\n")
                first = false
            } else {
                sb.append(' ')
            }
            sb.append(row.verse).append(' ').append(row.text.trim())
        }
        return sb.toString()
    }

    /**
     * [label] cut to [max] characters for the clipboard, its last one "…", never splitting a
     * surrogate pair; a label that fits is returned as it is. A long list of references makes a
     * label past what a clip may carry (`BibleClip.MAX_LABEL_CHARS`), and a clip whose label is
     * too long is one nothing can paste.
     */
    fun clipLabel(label: String, max: Int): String {
        val trimmed = label.trim()
        if (trimmed.length <= max) return trimmed
        var end = (max - 1).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(trimmed[end - 1])) end--
        return trimmed.substring(0, end).trimEnd() + "…"
    }
}
