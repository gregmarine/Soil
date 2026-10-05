package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SoilAddress

/**
 * **A document's links into the library**, read from its Markdown: every link whose address is a
 * [SoilAddress], once each, in the order they first appear. A link to anywhere else (a web
 * address) is the document's own business and is not listed.
 *
 * These are what the file's link mirror holds, so the library can answer "what links here".
 * A document has no pages, so every link sits on the page `""`: the item itself. Pure.
 */
object DocumentLinks {

    /** More links than this in one document are not mirrored: the mirror is a batch with the save. */
    const val MAX_MIRRORED = 2_000

    /** The page a document's links sit on: it has none, so the item itself. */
    const val PAGE = ""

    fun targets(markdown: String, documentId: String): List<SoilAddress> {
        if (markdown.indexOf(SoilAddress.SCHEME) < 0) return emptyList()
        val seen = LinkedHashSet<SoilAddress>()
        for (block in RichParse.parse(markdown).doc.blocks) {
            for (span in block.spans) {
                if (span.style != RichStyle.LINK) continue
                val address = SoilAddress.decode(span.url) ?: continue
                // A link never points at its own home.
                if (address.itemId != documentId) seen += address
            }
        }
        return seen.take(MAX_MIRRORED)
    }

    /**
     * The mirror made to say exactly [targets]: the page's links dropped, then one put for each.
     * Sent in the same batch as the words, so the mirror and the document cannot disagree. A
     * link's id is the document's with its place in the list: unique in the library, and the
     * same for the same document saved again.
     */
    fun mirror(documentId: String, targets: List<SoilAddress>): List<Statement> =
        listOf(Statement(SeamLinks.DROP_PAGE, PAGE)) +
            targets.mapIndexed { i, t -> Statement(SeamLinks.PUT, "$documentId:$i", PAGE, t.itemId, t.pageId) }
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
