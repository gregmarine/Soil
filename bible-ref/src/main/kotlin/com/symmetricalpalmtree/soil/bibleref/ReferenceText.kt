package com.symmetricalpalmtree.soil.bibleref

/**
 * **Typed words as a reference, and a wire as words**: what every app's dialog and link does
 * with a reference, in one place. The words parse by [ReferenceParser]'s grammar and their
 * chapters must exist ([Canon.chapterCount]); the verses are bounded by the parser alone, and the
 * reader says the rest. Pure.
 */
object ReferenceText {

    /** The wire [typed] names, or null when it is not a reference (or a chapter is out of range). */
    fun wireOf(typed: String): String? {
        val passages = ReferenceResolver.normalize(ReferenceParser.parseAll(typed), Canon::chapterCount)
        if (passages.isEmpty() || !ReferenceResolver.valid(passages, Canon::chapterCount) { true }) return null
        return ReferenceCodec.encode(passages)
    }

    /** The canonical label of [wire] ("John 3:14–18; Proverbs 3:5–6"), or null for a wire this build cannot read. */
    fun labelOf(wire: String): String? = ReferenceCodec.decode(wire)?.let { ReferenceCodec.label(it) }
}
