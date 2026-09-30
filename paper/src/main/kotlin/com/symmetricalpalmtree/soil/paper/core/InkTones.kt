package com.symmetricalpalmtree.soil.paper.core

/**
 * **The sixteen greys**: the one ladder every pen in the family draws from, darkest first, so
 * that level 0 is black and level 15 is white.
 *
 * What sits in a pref or crosses a seam is the **level**, never the ARGB: a build that retunes a
 * tone strands nothing anywhere, and a level this build does not offer reads as the caller's
 * fallback ([tone]), never as a crash. Level 15, white, is a stroke that shows nothing over bare
 * paper and covers what is under it; it is offered because a palette with a hole reads as broken.
 *
 * Four rows of four ([ROW_BREAK]), white first, as the palette lays them out. No Android here:
 * the numbers are the part worth testing.
 */
object InkTones {

    /** The tones as opaque ARGB, darkest first: level *n* is `TONES[n]`. */
    val TONES: List<Int> = listOf(
        0x000000, 0x505050, 0x606060, 0x686868, 0x707070, 0x808080, 0x888888, 0x909090,
        0xA0A0A0, 0xAAAAAA, 0xB6B6B6, 0xC0C0C0, 0xC8C8C8, 0xD0D0D0, 0xDDDDDD, 0xFFFFFF,
    ).map { (0xFF shl 24) or it }

    /** The levels this build offers, in order: the one line that changes which shades exist. */
    val LEVELS: List<Int> = TONES.indices.toList()

    const val BLACK: Int = 0
    const val WHITE: Int = 15

    /** Where the swatches wrap. */
    const val ROW_BREAK: Int = 4

    fun isLevel(level: Int): Boolean = level in LEVELS

    /** The grey [level] names, or [fallback]'s for a level this build does not offer. */
    fun tone(level: Int, fallback: Int = BLACK): Int =
        TONES[if (isLevel(level)) level else if (isLevel(fallback)) fallback else BLACK]

    /** [level] when this build offers it, else [fallback]: what a reader of a pref calls before
     *  it arms anything. */
    fun levelOrElse(level: Int, fallback: Int = BLACK): Int =
        if (isLevel(level)) level else if (isLevel(fallback)) fallback else BLACK

    /** The swatch rows as the palette lays them out: white first, [ROW_BREAK] to a row. */
    fun rows(): List<List<Int>> = LEVELS.asReversed().chunked(ROW_BREAK)
}
