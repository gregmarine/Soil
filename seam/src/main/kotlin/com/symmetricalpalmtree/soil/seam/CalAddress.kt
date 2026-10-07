package com.symmetricalpalmtree.soil.seam

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * **Where a link points, when it points at a day of the calendar**: an address a Markdown link
 * or a notebook's link payload can carry, beside [SoilAddress] and [BibleAddress].
 *
 * ```
 * cal:<yyyy-MM-dd>    e.g. cal:2026-10-06
 * ```
 *
 * Exactly ten characters after the scheme, digits and two dashes, naming a real day; nothing
 * else — no time, no half, no range. An address is untrusted input wherever it is read: [decode]
 * answers null for anything that is not exactly the shape. Pure.
 */
data class CalAddress(val date: String) {

    init {
        require(isDate(date)) { "a day is yyyy-MM-dd" }
    }

    fun encode(): String = "$SCHEME$date"

    companion object {
        const val SCHEME = "cal:"
        const val DATE_CHARS = 10

        fun isCal(address: String): Boolean = address.startsWith(SCHEME)

        fun decode(address: String): CalAddress? {
            if (!address.startsWith(SCHEME) || address.length != SCHEME.length + DATE_CHARS) return null
            val date = address.substring(SCHEME.length)
            return if (isDate(date)) CalAddress(date) else null
        }

        /** `yyyy-MM-dd`, ten characters, a day the calendar has. */
        fun isDate(text: String): Boolean {
            if (text.length != DATE_CHARS) return false
            for (i in text.indices) {
                val c = text[i]
                val ok = if (i == 4 || i == 7) c == '-' else c in '0'..'9'
                if (!ok) return false
            }
            return try {
                LocalDate.parse(text).toString() == text
            } catch (_: DateTimeParseException) {
                false
            }
        }
    }
}
