package com.symmetricalpalmtree.soil.paper.core

import java.util.Locale

/**
 * The one place a stroke's `color` column (`#RRGGBB` / `#AARRGGBB`) becomes an ARGB Int and back.
 * Pure Kotlin. Unparseable input decodes to opaque black.
 */
object InkColorCodec {

    const val BLACK: Int = 0xFF000000.toInt()

    /** `#AARRGGBB` when alpha is not 0xFF, else `#RRGGBB`. Upper-case hex, locale-independent. */
    fun encode(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        return if (a == 0xFF) String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
        else String.format(Locale.ROOT, "#%08X", argb)
    }

    /** Parse `#RRGGBB` or `#AARRGGBB` (case-insensitive). Anything else → [BLACK]. */
    fun decode(text: String?): Int {
        if (text == null) return BLACK
        val s = text.trim()
        if (s.length != 7 && s.length != 9) return BLACK
        if (s[0] != '#') return BLACK
        val hex = s.substring(1)
        // Every character a hex digit: `toLongOrNull` alone would take a sign (`#-12345`).
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return BLACK
        val v = hex.toLongOrNull(16) ?: return BLACK
        return if (hex.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }
}
