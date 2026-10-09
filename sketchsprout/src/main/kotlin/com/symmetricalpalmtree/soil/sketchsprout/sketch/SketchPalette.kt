package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.soil.paper.core.InkTones

/**
 * The sketch face's palette (Notesprout SN's arcs 44 and 46; the sizes and the marker, Greg,
 * 2026-10-08) — **the one place the three kinds' defaults and their size ladders are written
 * down**. The greys themselves are `:paper`'s [InkTones], Atelier's sixteen tones darkest first,
 * the ladder every paper surface in Soil hangs under its pen; a stored shade is a **level** on that
 * ladder, and a stored size is an **index** on the kind's [Ladder], so a level or an index this
 * build does not offer reads as the kind's default and nothing is ever migrated, and a size can be
 * retuned without stranding a saved choice.
 *
 * **The sizes are fixed ladders, not a dial** (Greg): the pencil 1, 2 and 4 px around the 1 px
 * hairline his hand settled on (SN, 2026-09-21: 4 → 2 → 1 the same evening), and a 5 mm lead for
 * shading, the width of the broad marker; the gel pen the tips as sold, 0.38, 0.5, 0.7 and
 * 1.0 mm — the default 0.5 mm is 5.9 px, a hair over the 5 px that stood until 2026-10-08 — and
 * under them 0.1 mm, the finest line the page can hold (1.2 px); the marker 1, 3 and 5 mm, the
 * fine, medium and broad tips, then 10 and 20 mm for a wash, badged "2x" and "4x" since their
 * samples fill the swatch alike (Greg's additions on M3's walk).
 *
 * **A millimetre is a millimetre on the page**, [PPI]: a sketch page is laid out at 300 ppi on
 * the Nomad and the Manta alike (1404 × 1872 and 1920 × 2560), and a width is baked into the page's
 * own pixels, so the pitch is the page's and not the screen's density. A device with another pitch
 * would change the page sizes with it; that is where this constant would move to the page.
 *
 * There is no Android here on purpose: the numbers and the rules that read them are the part worth
 * testing, and everything that draws them (`PaletteBar`) is the part that cannot be.
 */
object SketchPalette {

    /** The pencil's default shade: level 1, `#505050` — SN's one graphite tone, "spot on". */
    const val DEFAULT_SHADE: Int = 1

    /** The gel pen's default shade: black. */
    const val DEFAULT_PEN_SHADE: Int = InkTones.BLACK

    /** The marker's default shade: black, a mid grey through its translucency. */
    const val DEFAULT_MARKER_SHADE: Int = InkTones.BLACK

    /** The page's pitch, pixels per inch — the Nomad's and the Manta's. */
    const val PPI: Float = 300f

    /** [mm] on the page as px, to a tenth: `mm × PPI / 25.4`. */
    fun mmToPx(mm: Float): Float = Math.round(mm * PPI / 25.4f * 10f) / 10f

    /** One size on a ladder: its width on the page, the words the swatch is called by, and a
     *  [badge] the swatch wears when its sample could not be told from the one before it. */
    class Size(val px: Float, val label: String, val badge: String? = null)

    /** A kind's sizes in order, and which of them the kind starts on. */
    class Ladder(val sizes: List<Size>, val default: Int) {
        /** Whether [index] names a size on this ladder. */
        fun isIndex(index: Int): Boolean = index in sizes.indices

        /** The width [index] names, or the default's for an index this ladder does not have. */
        fun px(index: Int): Float = sizes[if (isIndex(index)) index else default].px
    }

    /** The pencil's leads: fine, medium, broad, and a shading lead as wide as the broad marker.
     *  Default 1 px, the hand's hairline. */
    val PENCIL_SIZES: Ladder = Ladder(listOf(Size(1f, "1 px"), Size(2f, "2 px"), Size(4f, "4 px"), Size(mmToPx(5f), "5 mm")), default = 0)

    /** The gel pen's tips as sold, under them the finest line there is. Default 0.5 mm. */
    val PEN_SIZES: Ladder = Ladder(
        listOf(Size(mmToPx(0.1f), "0.1 mm"), Size(mmToPx(0.38f), "0.38 mm"), Size(mmToPx(0.5f), "0.5 mm"), Size(mmToPx(0.7f), "0.7 mm"), Size(mmToPx(1.0f), "1.0 mm")),
        default = 2,
    )

    /** The marker's tips: fine, medium, broad, and a wide wash. Default 3 mm. */
    val MARKER_SIZES: Ladder = Ladder(
        listOf(Size(mmToPx(1f), "1 mm"), Size(mmToPx(3f), "3 mm"), Size(mmToPx(5f), "5 mm"), Size(mmToPx(10f), "10 mm", "2x"), Size(mmToPx(20f), "20 mm", "4x")),
        default = 1,
    )

    /** The ladder [kind] chooses its width from. */
    fun ladder(kind: SketchToolState.Kind): Ladder = when (kind) {
        SketchToolState.Kind.PENCIL -> PENCIL_SIZES
        SketchToolState.Kind.PEN -> PEN_SIZES
        SketchToolState.Kind.MARKER -> MARKER_SIZES
    }

    /** The shade [kind] starts on. */
    fun defaultShade(kind: SketchToolState.Kind): Int = when (kind) {
        SketchToolState.Kind.PENCIL -> DEFAULT_SHADE
        SketchToolState.Kind.PEN -> DEFAULT_PEN_SHADE
        SketchToolState.Kind.MARKER -> DEFAULT_MARKER_SHADE
    }

    /** Whether [level] names a shade this build offers. */
    fun isShade(level: Int): Boolean = InkTones.isLevel(level)

    /** The opaque ARGB grey [level] names — **or [fallback]'s**, for anything this build does not
     *  offer: a remembered 200 against this build is an ordinary miss, never a black page. */
    fun shade(level: Int, fallback: Int = DEFAULT_SHADE): Int = InkTones.tone(level, fallback)
}
