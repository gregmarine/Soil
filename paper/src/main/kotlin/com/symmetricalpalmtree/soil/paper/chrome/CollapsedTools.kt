package com.symmetricalpalmtree.soil.paper.chrome

import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.soil.paper.R

/**
 * The collapsed chrome's rules (arc 36 / C1), kept apart from the views so they can be tested:
 * which glyph the corner button wears for a tool, which mini-toolbar buttons read as armed, and
 * when a contact outside the rows takes them down.
 *
 * There is no dependency on Android views here on purpose — the decisions are the part worth
 * testing, and the view work ([CollapsedChrome]) is the part that cannot be. The drawable ids are
 * plain generated constants, so a JVM test can pin them.
 */
object CollapsedTools {

    /** The fixed order of the mini toolbar's tool buttons on a writing screen (decision 2 / 3):
     *  the two erasers are two buttons, so the lasso eraser is one tap away while collapsed. No
     *  smudge — it rubs pixels, and a writing screen's strokes have none; the sketch face, the one
     *  surface that has it, passes its own list. */
    val ORDER: List<Tool> = listOf(Tool.PEN, Tool.ERASER, Tool.LASSO_ERASER, Tool.LASSO)

    /**
     * The corner button's glyph for [tool]. The lasso wears the clipboard mark exactly as the
     * bar's button does while [clipboardLoaded] (arc 8's one standing hint that a pen tap on bare
     * paper will paste). [Tool.NONE] — a surface that captures nothing — wears the pen: the
     * button names what a tap will bring back, and the pen is what every screen arms first.
     *
     * The pen wears Tabler's `ballpen` (`ic_pen`, the user's call of 2026-09-22 — the pencil glyph
     * is for a true pencil, so it moved to `ic_pencil`, the sketch face's Pencil). The sketch face's
     * two pen **kinds** (arc 44 / T3, both `Tool.PEN`) never wear this resource: each paints its own
     * shade-filled glyph ([CollapsedChrome.PenKinds.primaryIcon] / `altIcon`), and the corner
     * button wears the armed kind's.
     */
    fun iconFor(tool: Tool, clipboardLoaded: Boolean = false): Int = when (tool) {
        Tool.PEN -> R.drawable.ic_pen
        Tool.NONE -> R.drawable.ic_pen
        Tool.ERASER -> R.drawable.ic_eraser
        // The stylus smudge (arc 50): Tabler's hand-finger — the finger's rub, on the nib.
        Tool.SMUDGE -> R.drawable.ic_smudge
        Tool.LASSO_ERASER -> R.drawable.ic_lasso_eraser
        Tool.LASSO -> if (clipboardLoaded) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
    }

    /**
     * Which of the PEN slot's **kind buttons** reads as armed (arc 44 / T3; N kinds since
     * Sketchsprout's marker, 2026-10-08) — the pencil's, the gel pen's and the marker's on the
     * sketch face, on the top bar ([PaperToolbar.sync]) and on the mini toolbar
     * ([CollapsedChrome.sync]) alike. A kind is an **index**: 0 is the primary pen button, 1 and
     * up the extras in bar order.
     *
     * It lives here, as one rule, because both bars ask it and two spellings of "is this the armed
     * pen?" would be two things to keep in step — the module's standing answer to the
     * `RattaNotebookView` sibling-copy trap, in miniature. The buttons are only ever lit under
     * [Tool.PEN], and then exactly one of them: [armedKind] says which kind the screen has armed,
     * [buttonKind] which kind this button offers, and they must agree.
     *
     * A screen with **one** pen passes the defaults (`0`, `0`) and gets `tool == PEN` back, which
     * is the rule it has always had.
     */
    fun penButtonSelected(tool: Tool, armedKind: Int = 0, buttonKind: Int = 0): Boolean =
        tool == Tool.PEN && armedKind == buttonKind

    /**
     * How many of the mini toolbar's [total] buttons its first **column** takes when the band
     * under the corner button holds [capacity] of them (2026-10-10): all of them when they fit,
     * else the capacity, and never fewer than one — a column with nothing in it is no toolbar,
     * and a band too short for one button is a geometry no device has. What is left over goes
     * to the second column beside it ([CollapsedChrome]), shown with the first on the same tap:
     * the corner button reveals the whole toolbar, not a door to the rest of it.
     */
    fun firstColumn(total: Int, capacity: Int): Int =
        if (total <= capacity) total else maxOf(1, capacity).coerceAtMost(total)

    /** The one mini-toolbar button that reads as armed under [tool]; none under [Tool.NONE]. Any
     *  real tool answers itself, not only [ORDER]'s: a screen's own list (the sketch face's
     *  smudge) borders its button by the same rule. */
    fun selectedFor(tool: Tool): Tool? = tool.takeIf { it != Tool.NONE }

    /**
     * Whether a contact at some point takes the rows down. Nothing showing → nothing to do; on
     * the collapsed chrome itself ([onChrome] — the corner button, whose own click toggles and
     * whose dismissal here would close-then-reopen, the lasso popup's trap, or the rows) → no;
     * inside a sub-bar the screen hung off the rows ([keep]) → no; anywhere else — a bare pen tap,
     * a stroke, a finger gesture, a bar button — yes.
     */
    fun outsideTapDismisses(showing: Boolean, onChrome: Boolean, keep: Boolean): Boolean =
        showing && !onChrome && !keep
}
