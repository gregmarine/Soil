package com.symmetricalpalmtree.soil.docsprout.data

/**
 * **A Bible link the writer took off, remembered**: the words as they stood and the wire they
 * named, kept with the document so the pass that links references as they are typed never puts
 * that one back (Greg's decision, 2026-10-05). Keyed by the words, whitespace folded and case
 * ignored, with the wire: the same reference written the same way anywhere in the document
 * stays plain; written another way, it is another reference. Pure.
 */
object BibleUnlinked {

    const val MAX_WORDS = 200

    fun words(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").lowercase().take(MAX_WORDS)

    fun key(words: String, wire: String): String = words(words) + "\u0000" + wire

    /** Whether [key] names [wire], whatever its words: a reference allowed again is allowed in every spelling. */
    fun names(key: String, wire: String): Boolean = key.endsWith("\u0000" + wire)
}
