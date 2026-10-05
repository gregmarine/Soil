package com.symmetricalpalmtree.soil.seam

/**
 * **Where a link in text points, when it points into the Bible**: a passage, written as an
 * address a Markdown link can carry, beside [SoilAddress].
 *
 * ```
 * bible:<wire>    e.g. bible:JHN:3:14-3:18,PRO:3:5-3:6
 * ```
 *
 * The wire is `:bible-ref`'s (`ReferenceCodec`): one `USFM:c:v-c:v` per range, joined by `,`.
 * The seam does not read it; it checks the character set and the cap, and the app that follows
 * the link decodes it. An address is untrusted input wherever it is read: [decode] answers null
 * for anything that is not exactly the shape. Pure.
 */
data class BibleAddress(val wire: String) {

    init {
        require(isWire(wire)) { "a wire is [A-Z0-9:,-]{1..$MAX_WIRE_CHARS}" }
    }

    fun encode(): String = "$SCHEME$wire"

    companion object {
        const val SCHEME = "bible:"
        const val MAX_WIRE_CHARS = 512

        fun isBible(address: String): Boolean = address.startsWith(SCHEME)

        fun decode(address: String): BibleAddress? {
            if (!address.startsWith(SCHEME) || address.length > SCHEME.length + MAX_WIRE_CHARS) return null
            val wire = address.substring(SCHEME.length)
            return if (isWire(wire)) BibleAddress(wire) else null
        }

        /** The characters a wire can hold, and nothing a payload or an address cannot carry. */
        fun isWire(wire: String): Boolean =
            wire.isNotEmpty() && wire.length <= MAX_WIRE_CHARS &&
                wire.all { it in 'A'..'Z' || it in '0'..'9' || it == ':' || it == '-' || it == ',' }
    }
}
