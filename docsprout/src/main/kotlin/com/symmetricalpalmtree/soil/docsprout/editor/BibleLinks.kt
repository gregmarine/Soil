package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.bibleref.Canon
import com.symmetricalpalmtree.soil.bibleref.ReferenceCodec
import com.symmetricalpalmtree.soil.bibleref.ReferenceParser
import com.symmetricalpalmtree.soil.bibleref.ReferenceResolver
import com.symmetricalpalmtree.soil.seam.BibleAddress

/**
 * **A Bible reference, as a document's link.** Typed into the Link dialog, the words become a
 * `bible:` address when they parse as a reference whose chapters exist; a link's address reads
 * back as the canonical label ("John 3:14–18"). Pure.
 */
object BibleLinks {

    /** The wire [typed] names, or null when it is not a reference (or a chapter is out of range). */
    fun wireOf(typed: String): String? {
        val passages = ReferenceResolver.normalize(ReferenceParser.parseAll(typed), Canon::chapterCount)
        if (passages.isEmpty() || !ReferenceResolver.valid(passages, Canon::chapterCount) { true }) return null
        return ReferenceCodec.encode(passages)
    }

    /** The address a reference links to. */
    fun addressOf(wire: String): String = BibleAddress(wire).encode()

    /** The wire a `bible:` address carries, or null for any other address. */
    fun wireOfAddress(url: String): String? = BibleAddress.decode(url)?.wire?.takeIf { ReferenceCodec.decode(it) != null }

    /** The canonical label of a `bible:` address, or null for any other. */
    fun labelOf(url: String): String? = wireOfAddress(url)?.let { ReferenceCodec.decode(it) }?.let { ReferenceCodec.label(it) }
}
