package com.symmetricalpalmtree.soil.seam

/**
 * **Where a link in text points, when it points into the library**: an item, or a page of one,
 * written as an address a Markdown link can carry.
 *
 * ```
 * soil:<itemId>             the whole item
 * soil:<itemId>/<pageId>    a page of it
 * ```
 *
 * Ids only, never a name: the library is the only place a name lives, and a renamed item is
 * still the item. An address is untrusted input wherever it is read: [decode] answers null for
 * anything that is not exactly one of the two shapes. Pure.
 */
data class SoilAddress(val itemId: String, val pageId: String? = null) {

    init {
        require(validId(itemId)) { "an item id is [A-Za-z0-9-]{1..$MAX_ID_CHARS}" }
        require(pageId == null || validId(pageId)) { "a page id is [A-Za-z0-9-]{1..$MAX_ID_CHARS}" }
    }

    fun encode(): String = if (pageId == null) "$SCHEME$itemId" else "$SCHEME$itemId/$pageId"

    companion object {
        const val SCHEME = "soil:"
        const val MAX_ID_CHARS = 64

        fun isSoil(address: String): Boolean = address.startsWith(SCHEME)

        fun decode(address: String): SoilAddress? {
            if (!address.startsWith(SCHEME) || address.length > SCHEME.length + 2 * MAX_ID_CHARS + 1) return null
            val parts = address.substring(SCHEME.length).split('/')
            if (parts.isEmpty() || parts.size > 2 || !parts.all(::validId)) return null
            return SoilAddress(parts[0], parts.getOrNull(1))
        }

        private fun validId(id: String): Boolean =
            id.isNotEmpty() && id.length <= MAX_ID_CHARS && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}
