package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.bibleref.ReferenceCodec
import com.symmetricalpalmtree.soil.bibleref.VerseRange
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.BibleAddress
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SoilAddress

/**
 * **A document's links into the library and into the Bible**, read from its Markdown: every
 * link whose address is a [SoilAddress] or a [BibleAddress], once each, in the order they first
 * appear. A link to anywhere else (a web address) is the document's own business and is not
 * listed.
 *
 * These are what the file's link mirror holds, so the library can answer "what links here" and
 * the Bible's reader "what links into these verses". A document has no pages, so every link sits
 * on the page `""`: the item itself. A link into the Bible is mirrored once per range of its
 * wire. Pure.
 */
object DocumentLinks {

    /** More links than this in one document are not mirrored: the mirror is a batch with the save. */
    const val MAX_MIRRORED = 2_000

    /** The page a document's links sit on: it has none, so the item itself. */
    const val PAGE = ""

    /** Where a mirrored link points: an item (or a page of one), or into the Bible. */
    sealed interface Target {
        data class Item(val address: SoilAddress) : Target
        data class Bible(val wire: String, val ranges: List<VerseRange>) : Target
    }

    fun targets(markdown: String, documentId: String): List<Target> {
        if (markdown.indexOf(SoilAddress.SCHEME) < 0 && markdown.indexOf(BibleAddress.SCHEME) < 0) return emptyList()
        val seen = LinkedHashSet<Target>()
        for (block in RichParse.parse(markdown).doc.blocks) {
            for (span in block.spans) {
                if (span.style != RichStyle.LINK) continue
                val target = targetOf(span.url, documentId) ?: continue
                seen += target
            }
        }
        return seen.take(MAX_MIRRORED)
    }

    /** What one address is, as a mirror target; null for an address the mirror does not carry. */
    fun targetOf(url: String, documentId: String): Target? {
        SoilAddress.decode(url)?.let { address ->
            // A link never points at its own home.
            return if (address.itemId == documentId) null else Target.Item(address)
        }
        val bible = BibleAddress.decode(url) ?: return null
        val passages = ReferenceCodec.decode(bible.wire) ?: return null
        return Target.Bible(bible.wire, passages.flatMap { it.ranges })
    }

    /**
     * The mirror made to say exactly [targets]: the page's links dropped, then one put for each,
     * and one per range for a link into the Bible. Sent in the same batch as the words, so the
     * mirror and the document cannot disagree. A link's id is the document's with its place in
     * the list (and the range's within it): unique in the library, and the same for the same
     * document saved again.
     */
    fun mirror(documentId: String, targets: List<Target>): List<Statement> {
        val out = ArrayList<Statement>(targets.size + 1)
        out += Statement(SeamLinks.DROP_PAGE, PAGE)
        targets.forEachIndexed { i, t ->
            val id = "$documentId:$i"
            when (t) {
                is Target.Item -> out += Statement(SeamLinks.PUT, id, PAGE, t.address.itemId, t.address.pageId)
                is Target.Bible -> t.ranges.forEachIndexed { n, r ->
                    out += Statement(SeamLinks.PUT_BIBLE, SeamLinks.rangeId(id, n), PAGE, t.wire, r.startKey.toLong(), r.endKey.toLong())
                }
            }
        }
        return out
    }
}

/**
 * Where the writer came from, link by link, so there is a way back: following a link from one
 * document into another replaces the document on screen, and Back returns. A bounded stack of
 * document ids, newest last, and its stored form, one id a line. Pure; what is stored is
 * untrusted, and a line that is not an id is dropped.
 */
object DocTrail {
    const val MAX_ENTRIES = 50

    fun push(trail: List<String>, documentId: String): List<String> = (trail + documentId).takeLast(MAX_ENTRIES)

    /** The newest entry and what is left, or null and nothing when there is none. */
    fun pop(trail: List<String>): Pair<String?, List<String>> =
        if (trail.isEmpty()) null to emptyList() else trail.last() to trail.dropLast(1)

    fun encode(trail: List<String>): String = trail.joinToString("\n")

    fun decode(stored: String?): List<String> =
        if (stored.isNullOrEmpty()) emptyList()
        else stored.split('\n').filter { line -> line.isNotEmpty() && line.length <= SoilAddress.MAX_ID_CHARS && line.all { it.isLetterOrDigit() || it == '-' } }.takeLast(MAX_ENTRIES)
}
