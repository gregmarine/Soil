package com.symmetricalpalmtree.soil.paper.chrome

import android.view.View
import androidx.core.view.doOnNextLayout
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * Hides / shows all of a paper screen's chrome bars — the arc-33 double-tap toggle, one copy for
 * the four screens (notebook, sticky editor, scratch pad, calendar) so the flip order never drifts.
 *
 * **The flip order, once:**
 * 1. `paper.releaseRender()` — unless [apply]'s `releaseRender` is false, when nothing is on
 *    the glass yet;
 * 2. hiding → [beforeHide]: the consumer takes down its button-anchored popups (lasso, tags,
 *    insert, eraser) whose button is about to go; showing → [beforeShow]: the rows hung off the
 *    corner button go the same way (arc 36);
 * 3. every bar `GONE` / `VISIBLE` — **never `INVISIBLE`**: an attached Ratta paper view keeps the
 *    pen claimed whatever a sibling's visibility, and an `INVISIBLE` bar keeps its rect — and
 *    every [whileHidden] view the inverse (arc 36: the corner tool button);
 * 4. `root.doOnNextLayout { afterLayout() }` — the consumer's `pushExclusions()`, which re-reads the
 *    band ([ChromeBand]), the rects (`PaperToolbar.rectOf`, visibility-aware) and, on the notebook,
 *    the snap margin. One binder call per flip.
 *
 * Deliberately **not** pen-idle-gated: the act that asks for a flip already passed
 * `PageGestures.gateOpen()` and its escrow, and `isPenActive` counts hover — the bars would arrive
 * long after the taps. It is a chrome frame at a deliberate act (`docs/notebook.md` § frame-silence,
 * riding exception 6). Nothing here touches `setPageSize`: the paper view never resizes on a flip.
 */
class ChromeToggle(
    private val paper: PaperView,
    private val root: View,
    private val bars: List<View>,
    private val beforeHide: () -> Unit,
    private val afterLayout: () -> Unit,
    /**
     * Told the new state every time it actually changes — the host screens persist it
     * (`ChromePrefs`), the extension screens ignore it (an extension writes nothing to disk, and
     * the host reads the state off the result Intent). Here rather than after each `toggle()` call
     * because three screens had grown the same "flip, then write the flag" pair.
     */
    private val onChanged: (Boolean) -> Unit = {},
    /**
     * Views that live only while the bars are hidden — arc 36's corner tool button. They take the
     * inverse visibility of [bars] in the same flip, so the corner button is never up beside a
     * bar and never absent over bare paper.
     */
    private val whileHidden: List<View> = emptyList(),
    /** The hide → show counterpart of [beforeHide]: the consumer takes down the rows hung off the
     *  corner button, whose button is about to go (arc 36). */
    private val beforeShow: () -> Unit = {},
) {
    /** The current state; `false` (shown) until the first [apply]. */
    var hidden: Boolean = false
        private set

    /**
     * Put the chrome into [hidden]. Pass `releaseRender = false` when nothing is on the glass yet
     * — the `onCreate` first application, and [sync]'s re-read before the paper comes back.
     *
     * Unconditional: the first application must set every bar's visibility even when [hidden] is
     * already the state it holds. "Nothing changed" is [sync]'s question, not this one's.
     */
    fun apply(hidden: Boolean, releaseRender: Boolean = true) {
        val changed = this.hidden != hidden
        this.hidden = hidden
        if (releaseRender) paper.releaseRender()
        // The hooks name a transition, so they fire only on one: the first application from
        // `onCreate` (nothing is up yet) and a re-apply of the same state call neither.
        if (changed) { if (hidden) beforeHide() else beforeShow() }
        val visibility = if (hidden) View.GONE else View.VISIBLE
        bars.forEach { it.visibility = visibility }
        val inverse = if (hidden) View.VISIBLE else View.GONE
        whileHidden.forEach { it.visibility = inverse }
        Slog.d(TAG) { "chrome hidden=$hidden" }
        root.doOnNextLayout { afterLayout() }
        root.requestLayout()
        if (changed) onChanged(hidden)
    }

    /**
     * The resume rule, once: another paper screen may have flipped the one global flag while this
     * one was away, so re-read it before the paper comes back. A no-op when nothing changed, and
     * never a render release — this runs before `resumeDrawing()`, with nothing on the glass.
     */
    fun sync(persisted: Boolean) {
        if (hidden == persisted) return
        apply(persisted, releaseRender = false)
    }

    fun toggle() = apply(!hidden)

    private companion object { const val TAG = "ChromeToggle" }
}
