package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.soil.paper.core.InkTones

/**
 * The sketch face's palette (Notesprout SN's arcs 44 and 46) — **the one place the two widths and
 * the two kinds' defaults are written down**. The greys themselves are `:paper`'s [InkTones],
 * Atelier's sixteen tones darkest first, the ladder every paper surface in Soil hangs under its
 * pen; a stored shade is a **level** on that ladder, so a level this build does not offer reads as
 * the kind's default and nothing is ever migrated.
 *
 * **There is no size choice**: the pencil is one width, [PENCIL_WIDTH_PX]; the gel pen is
 * [PEN_WIDTH_PX]. Both are Greg's hand's answers on the Nomad (SN, 2026-09-21 and T3's walk).
 *
 * There is no Android here on purpose: the numbers and the rules that read them are the part worth
 * testing, and everything that draws them (`PaletteBar`) is the part that cannot be.
 */
object SketchPalette {

    /** The pencil's default shade: level 1, `#505050` — SN's one graphite tone, "spot on". */
    const val DEFAULT_SHADE: Int = 1

    /** The gel pen's default shade: black. */
    const val DEFAULT_PEN_SHADE: Int = InkTones.BLACK

    /** The pencil's one lead width in px: **1** since 2026-09-21 (SN: 4 → 2 → 1 the same evening);
     *  at 1 px every fleck is capped at the lead's width, so the mark is a grained hairline. */
    const val PENCIL_WIDTH_PX: Float = 1f

    /** The gel pen's width in px: 3 was "a tad small", 7 too heavy, **5** stood. */
    const val PEN_WIDTH_PX: Float = 5f

    /** Whether [level] names a shade this build offers. */
    fun isShade(level: Int): Boolean = InkTones.isLevel(level)

    /** The opaque ARGB grey [level] names — **or [fallback]'s**, for anything this build does not
     *  offer: a remembered 200 against this build is an ordinary miss, never a black page. */
    fun shade(level: Int, fallback: Int = DEFAULT_SHADE): Int = InkTones.tone(level, fallback)
}
