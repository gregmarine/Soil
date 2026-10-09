package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * What the sketch face's drawing tools are set to (Notesprout SN's arcs 44 and 46; the marker and
 * the sizes, Greg, 2026-10-08) — the armed kind, and each kind's shade and size, the shades as
 * levels on [SketchPalette]'s ladder and the sizes as indexes on the kind's own. It is the face's
 * whole tool state and the only thing the device remembers of it.
 *
 * **One state, three kinds.** The pencil, the gel pen and the marker are all `Tool.PEN` to g-paper
 * — they differ only in the style, width and colour the engine is armed with — so "which pen is
 * armed" cannot be asked of the engine and lives here, where the bar reads it and the buttons
 * paint from it. **Each kind keeps its own shade and size** through a switch to another and back.
 * The kinds' order is the bars' order: a kind's index on the top bar and the mini toolbar is its
 * ordinal.
 *
 * **The eraser is not in here** and never will be: it has no settings of its own, and a face that
 * opened on the eraser would read as a broken pencil. That is also why [kind] is always a pen
 * kind: with the rubber armed, the palette edits the kind last armed. The smudge likewise.
 *
 * **Out of range is the default, never an exception** ([of]): every number read from outside
 * (a preference) lands on something legal, each field falling back on its own default
 * independently — a remembered pen with a shade this build no longer has is still a remembered pen.
 */
data class SketchToolState(
    val kind: Kind,
    /** Every kind's shade and size, each kind present. */
    val settings: Map<Kind, Setting>,
) {

    /** The pen's kinds, in bar order. All `Tool.PEN` to the engine. */
    enum class Kind { PENCIL, PEN, MARKER }

    /** One kind's shade (a ladder level) and size (an index on its [SketchPalette.Ladder]). */
    data class Setting(val shade: Int, val size: Int)

    /** The armed kind's setting. */
    val armed: Setting get() = settings.getValue(kind)

    /** What the armed kind draws with — graphite, the uniform line of a pen, or the marker's
     *  translucent pass. */
    val penStyle: StrokeStyle get() = when (kind) {
        Kind.PENCIL -> StrokeStyle.PENCIL
        Kind.PEN -> StrokeStyle.PEN
        Kind.MARKER -> StrokeStyle.MARKER
    }

    /** The armed kind's width in px, from its ladder. */
    val penWidth: Float get() = SketchPalette.ladder(kind).px(armed.size)

    /** The armed kind's colour: its own chosen grey. */
    val penColor: Int get() = report(kind)

    /** The armed kind's shade, as a ladder level — what the palette paints as selected. */
    val armedShade: Int get() = armed.shade

    /** The armed kind's size, as an index on its ladder — what the palette's size row paints as
     *  selected. */
    val armedSize: Int get() = armed.size

    /** The grey [k]'s button reports — that kind's own shade, whatever kind is armed: a button
     *  says what a tap on it will bring back. */
    fun report(k: Kind): Int = SketchPalette.shade(settings.getValue(k).shade, SketchPalette.defaultShade(k))

    /** Arm a kind, keeping every kind's settings — a switch is not a reset. */
    fun withKind(kind: Kind): SketchToolState = of(kind, settings)

    /** Pick a shade **for the armed kind** — the palette's one verb for shades. No other kind moves. */
    fun withShade(level: Int): SketchToolState = of(kind, settings + (kind to armed.copy(shade = level)))

    /** Pick a size **for the armed kind** — the palette's one verb for sizes. No other kind moves. */
    fun withSize(index: Int): SketchToolState = of(kind, settings + (kind to armed.copy(size = index)))

    companion object {

        /** The face with nothing remembered: the pencil at `#505050` (level 1) and 1 px, the pen at
         *  black and 0.5 mm, the marker at black and 3 mm. */
        val DEFAULT: SketchToolState = of(Kind.PENCIL, emptyMap())

        /** A state from numbers of unknown provenance: each kind's shade and size falls back on its
         *  own default independently, and a kind missing from [raw] reads as its defaults. */
        fun of(kind: Kind, raw: Map<Kind, Setting>): SketchToolState = SketchToolState(
            kind = kind,
            settings = Kind.entries.associateWith { k ->
                val r = raw[k]
                Setting(
                    shade = r?.shade?.takeIf { SketchPalette.isShade(it) } ?: SketchPalette.defaultShade(k),
                    size = r?.size?.takeIf { SketchPalette.ladder(k).isIndex(it) } ?: SketchPalette.ladder(k).default,
                )
            },
        )

        /** [of] from a stored kind name: an unrecognised one reads as the pencil, the face's own
         *  first answer. */
        fun of(kindName: String?, raw: Map<Kind, Setting>): SketchToolState =
            of(Kind.entries.firstOrNull { it.name == kindName } ?: Kind.PENCIL, raw)
    }
}
