package com.symmetricalpalmtree.soil.docsprout.editor.bible

import com.symmetricalpalmtree.soil.bibleref.ReferenceScan
import com.symmetricalpalmtree.soil.docsprout.data.BibleUnlinked
import com.symmetricalpalmtree.soil.docsprout.editor.BibleLinks
import com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadCheck
import com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadTokenizer

/**
 * **References in a document's words, found and made into links**: the pure half of the pass
 * that runs after typing stops (Greg's decision, 2026-10-05). [plan] finds what to link in a
 * region of the text; [rewriteMarkdown] is the Markdown source's half, where a link is
 * characters; the rendered document's half is a span over the words, which needs the editor.
 *
 * What is **not** linked: words inside an existing link, a code span or a raw block
 * ([protected]); a reference the writer took the link off ([BibleUnlinked]); and a reference
 * the caret is touching, which may still be being typed, left for the pass after the caret has
 * moved on. Pure.
 */
object ReferenceLinker {

    /** One reference to link: its words `[start, end)`, and the wire they name. */
    data class Hit(val start: Int, val end: Int, val words: String, val wire: String) {
        val address: String get() = BibleLinks.addressOf(wire)
    }

    /** Whether a tick that found nothing to do should look again once the caret has moved. */
    class Plan(val hits: List<Hit>, val heldByCaret: Boolean)

    /**
     * The references in [text] within [region] (grown to whole lines), none of whose characters
     * are [protected], none in [unlinked], none touching [caret] (a caret anywhere in the words,
     * or at either end of them).
     */
    fun plan(text: String, region: ProofreadCheck.Region, protected: BooleanArray, unlinked: Set<String>, caret: Int?): Plan {
        val lines = ProofreadCheck.lineRegion(text, region.start, region.end)
        if (lines.end <= lines.start) return Plan(emptyList(), false)
        val slice = text.substring(lines.start, lines.end)
        val hits = ArrayList<Hit>()
        var held = false
        for (found in ReferenceScan.scan(slice)) {
            val start = lines.start + found.start
            val end = lines.start + found.end
            if ((start until end).any { it < protected.size && protected[it] }) continue
            val words = text.substring(start, end)
            // A link is one line's words: one across a line break would join two blocks.
            if (words.indexOf('\n') >= 0) continue
            val wire = found.wire
            if (BibleUnlinked.key(words, wire) in unlinked) continue
            if (caret != null && caret in start..end) { held = true; continue }
            hits += Hit(start, end, words, wire)
        }
        return Plan(hits, held)
    }

    /**
     * The reference the caret or the selection `[from, to]` touches in [text], if any: a hit on
     * the caret's line that the range overlaps or sits at either end of. What the Link tool offers
     * when nothing is linked yet, which is how an unlinked reference is linked again.
     */
    fun hitAt(text: String, from: Int, to: Int): Hit? {
        val a = minOf(from, to).coerceIn(0, text.length)
        val b = maxOf(from, to).coerceIn(0, text.length)
        val lines = ProofreadCheck.lineRegion(text, a, b)
        if (lines.end <= lines.start) return null
        val slice = text.substring(lines.start, lines.end)
        return ReferenceScan.scan(slice)
            .map { Hit(lines.start + it.start, lines.start + it.end, text.substring(lines.start + it.start, lines.start + it.end), it.wire) }
            .filter { it.words.indexOf('\n') < 0 }
            .firstOrNull { a <= it.end && b >= it.start }
    }

    /** What is not prose in Markdown source: code, addresses, and every link whole. */
    fun markdownProtected(text: String): BooleanArray {
        val skip = ProofreadTokenizer.skipMask(text)
        for (m in LINK.findAll(text)) java.util.Arrays.fill(skip, m.range.first, m.range.last + 1, true)
        return skip
    }

    /** The Markdown with every hit written as a link, last first so no offset moves under
     *  another, and where [caret] lands after it. */
    fun rewriteMarkdown(text: String, hits: List<Hit>, caret: Int): Pair<String, Int> {
        val sb = StringBuilder(text)
        var moved = caret
        for (hit in hits.sortedByDescending { it.start }) {
            val link = "[${hit.words}](${hit.address})"
            sb.replace(hit.start, hit.end, link)
            if (hit.end <= caret) moved += link.length - (hit.end - hit.start)
        }
        return sb.toString() to moved
    }

    private val LINK = Regex("""!?\[[^\]\n]*]\([^)\n]*\)""")
}
