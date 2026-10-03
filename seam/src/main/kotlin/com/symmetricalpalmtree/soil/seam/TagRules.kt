package com.symmetricalpalmtree.soil.seam

import java.util.Locale
import java.util.UUID

/**
 * What makes two pieces of typed text **the same tag**. Pure, stdlib only, shared by both sides
 * of the seam: an app validates before it asks, Soil validates again on the way in, and they
 * must agree to the character.
 *
 * The rule: trim the ends, collapse internal whitespace runs to one space, fold case. "  Reading
 * List " and "reading list" are one tag. Nothing else is restricted: multi-word tags are the
 * point, punctuation and digits are ordinary text, and the only bound is [MAX_TAG_CHARS],
 * measured on the display form.
 *
 *  - [display] is what a person sees and what is stored. Case is **kept**: a tag wears the
 *    casing of whoever entered it first.
 *  - [identityKey] is what "already exists?" is asked with: [display] folded with [Locale.ROOT],
 *    never the device locale, so the same library answers the same on every device.
 */
object TagRules {

    /** A tag's display form, in characters. */
    const val MAX_TAG_CHARS = 64

    /** Tags in one library. */
    const val MAX_TAGS = 5_000

    /** Assignments in one library. */
    const val MAX_ASSIGNMENTS = 50_000

    /** What an assign refused by a cap is refused with. Compared verbatim. */
    const val TAGS_FULL = "the library holds as many tags as it can"

    /** [text] as it will be shown and stored: ends trimmed, every internal run of whitespace
     *  one space, case untouched. A normalized tag carries no tab and no newline. */
    fun display(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                sb.append(' ')
                pendingSpace = false
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /** The identity of the tag [text] names: its [display] form, case-folded locale-neutrally. */
    fun identityKey(text: String): String = display(text).lowercase(Locale.ROOT)

    /** Something is left after normalizing, and it fits [MAX_TAG_CHARS]. */
    fun isValid(text: String): Boolean {
        val d = display(text)
        return d.isNotEmpty() && d.length <= MAX_TAG_CHARS
    }

    /**
     * [text] as a starting point for a tag field: normalized, and cut to [MAX_TAG_CHARS] without
     * splitting a surrogate pair; null when nothing is left of it.
     */
    fun prefill(text: String): String? {
        val d = display(text)
        if (d.isEmpty()) return null
        if (d.length <= MAX_TAG_CHARS) return d
        var end = MAX_TAG_CHARS
        if (d[end - 1].isHighSurrogate()) end--
        return d.substring(0, end).takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [id] is a canonical UUID, the one shape an item id, a page id or a tag id takes at
     * every door of the seam. `UUID.fromString` is lenient, so the parse is round-tripped and
     * only the `8-4-4-4-12` form is accepted; hex case is not significant.
     */
    fun isId(id: String): Boolean {
        val parsed = try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return parsed.toString().equals(id, ignoreCase = true)
    }
}
