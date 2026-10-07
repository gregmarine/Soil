package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * What the sketch face's drawing tools are set to (Notesprout SN's arcs 44 and 46) — the armed
 * kind, the pencil's shade and the gel pen's shade, the shades as levels on [SketchPalette]'s
 * ladder. It is the face's whole tool state and the only thing the device remembers of it.
 *
 * **One state, two kinds.** The pencil and the gel pen are both `Tool.PEN` to g-paper — they differ
 * only in the style, width and colour the engine is armed with — so "which pen is armed" cannot
 * be asked of the engine and lives here, where the bar reads it and the buttons paint from it.
 * **Each kind keeps its own shade** through a switch to the other and back.
 *
 * **The eraser is not in here** and never will be: it has no settings of its own, and a face that
 * opened on the eraser would read as a broken pencil. That is also why [kind] is always a pen
 * kind: with the rubber armed, the palette edits the kind last armed.
 *
 * **Out of range is the default, never an exception** ([of]): every number read from outside
 * (a preference) lands on something legal, each field falling back on its own default
 * independently — a remembered pen with a shade this build no longer has is still a remembered pen.
 */
data class SketchToolState(
    val kind: Kind,
    /** The pencil's grey, as a level of the ladder. Kept while the pen is armed. */
    val pencilShade: Int,
    /** The gel pen's grey, as a level of the same ladder. Kept while the pencil is armed. */
    val penShade: Int,
) {

    /** The pen's two kinds. Both `Tool.PEN` to the engine. */
    enum class Kind { PENCIL, PEN }

    /** Whether the **gel pen** is the armed kind rather than the pencil. */
    val isPen: Boolean get() = kind == Kind.PEN

    /** What the armed kind draws with — graphite, or the uniform line of a pen. */
    val penStyle: StrokeStyle get() = if (isPen) StrokeStyle.PEN else StrokeStyle.PENCIL

    /** The armed kind's width in px. */
    val penWidth: Float get() = if (isPen) SketchPalette.PEN_WIDTH_PX else SketchPalette.PENCIL_WIDTH_PX

    /** The armed kind's colour: its own chosen grey. */
    val penColor: Int get() = if (isPen) penReport else pencilReport

    /** The armed kind's shade, as a ladder level — what the palette paints as selected. */
    val armedShade: Int get() = if (isPen) penShade else pencilShade

    /** The grey the **Pencil button** reports — the pencil's own shade, whatever kind is armed: a
     *  button says what a tap on it will bring back. */
    val pencilReport: Int get() = SketchPalette.shade(pencilShade, SketchPalette.DEFAULT_SHADE)

    /** The grey the **Pen button** reports — the gel pen's own shade, whatever kind is armed. */
    val penReport: Int get() = SketchPalette.shade(penShade, SketchPalette.DEFAULT_PEN_SHADE)

    /** Arm a kind, keeping both shades — a switch is not a reset. */
    fun withKind(kind: Kind): SketchToolState = of(kind, pencilShade, penShade)

    /** Pick a shade **for the armed kind** — the palette's one verb. The other kind's shade does not move. */
    fun withShade(level: Int): SketchToolState =
        if (isPen) of(kind, pencilShade, level) else of(kind, level, penShade)

    companion object {

        /** The face with nothing remembered: the pencil at `#505050` (level 1), the pen at black. */
        val DEFAULT: SketchToolState = SketchToolState(Kind.PENCIL, SketchPalette.DEFAULT_SHADE, SketchPalette.DEFAULT_PEN_SHADE)

        /** A state from numbers of unknown provenance: each field falls back on its own default. */
        fun of(kind: Kind, pencilShade: Int, penShade: Int): SketchToolState = SketchToolState(
            kind = kind,
            pencilShade = if (SketchPalette.isShade(pencilShade)) pencilShade else SketchPalette.DEFAULT_SHADE,
            penShade = if (SketchPalette.isShade(penShade)) penShade else SketchPalette.DEFAULT_PEN_SHADE,
        )

        /** [of] from a stored kind name: an unrecognised one reads as the pencil, the face's own
         *  first answer. */
        fun of(kindName: String?, pencilShade: Int, penShade: Int): SketchToolState =
            of(if (kindName == Kind.PEN.name) Kind.PEN else Kind.PENCIL, pencilShade, penShade)
    }
}
