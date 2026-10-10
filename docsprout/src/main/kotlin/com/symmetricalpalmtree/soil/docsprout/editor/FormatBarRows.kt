package com.symmetricalpalmtree.soil.docsprout.editor

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The format bar's rows: what does not fit on the bar wraps onto rows below it, and every tool is
 * on the glass at all times (2026-10-10 — before this the tail sat behind a `…` in a panel that
 * opened on a tap; now there is no `…` and nothing to open).
 *
 * The two properties that make it worth having are both about *not* losing the tools:
 *
 * - **Views are MOVED, never cloned.** A tool on a lower row is the same object that was on the
 *   bar, so its click listener, its long-press hint and its content description come with it and
 *   cannot drift out of step with a copy.
 * - **The rows are in flow, below the bar.** They push the text down instead of floating over it —
 *   on e-ink an overlay leaves a ghost of itself, and the rows are part of the bar, not a menu.
 *
 * The full palette is twenty-two tools plus six separators; a Nomad cannot show it on one row. A
 * bar that scrolled would hide its tail with no sign that there is one, so the tail wraps — and it
 * always wraps at the same place for a given width, so muscle memory still holds.
 *
 * Two things a caller must honour, both of which the arithmetic depends on: every moveable child
 * needs an **exact px** width in its `LayoutParams` (`WRAP_CONTENT` measures as 0 here), and group
 * separators must be plain [View] instances — that is how [isDivider] tells a separator from a tool.
 */
class FormatBarRows(
    private val bar: LinearLayout,
    /** The rows under the bar; `GONE` while everything fits on it. */
    private val rows: LinearLayout,
) {

    /** Every item (tools and their separators) in bar order. */
    private var originalOrder: List<View> = emptyList()
    private var initialized = false

    /** The bar width the current arrangement was cut for. */
    private var lastWidth = 0

    /**
     * Work out what fits once the bar has a width, and again whenever that width changes — the watch
     * belongs here rather than in the host because what it guards is this class's own arithmetic.
     *
     * Guarded on the width itself: the listener also fires for layout passes that change nothing,
     * and a recalc rebuilds the bar, which would loop.
     */
    fun watchWidth() {
        bar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastWidth) {
                lastWidth = width
                bar.post { recalc() }
            }
        }
    }

    // ── The cut ───────────────────────────────────────────────────────────────

    private fun initialize() {
        if (initialized) return
        originalOrder = (0 until bar.childCount).map { bar.getChildAt(it) }
        initialized = true
    }

    /**
     * Work out what fits and rebuild the bar and the rows. Safe to call on every width change.
     */
    fun recalc() {
        initialize()
        val available = bar.width - bar.paddingStart - bar.paddingEnd
        if (available <= 0) return

        for (child in originalOrder) (child.parent as? ViewGroup)?.removeView(child)
        bar.removeAllViews()
        rows.removeAllViews()

        // Pack greedily, in bar order: the bar first, then rows of the same width. A row never
        // begins or ends with a group separator — against the edge it would read as a stray line.
        var row: LinearLayout = bar
        var used = 0
        for (item in originalOrder) {
            val w = naturalWidth(item)
            if (used > 0 && used + w > available) {
                dropTrailingDivider(row)
                row = newRow().also { rows.addView(it) }
                used = 0
                if (isDivider(item)) continue
            }
            row.addView(item)
            used += w
        }
        dropTrailingDivider(row)
        // GONE, not empty-but-visible: an empty row container with padding is a blank band.
        rows.visibility = if (rows.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun dropTrailingDivider(row: LinearLayout) {
        val last = row.childCount - 1
        if (last >= 0 && isDivider(row.getChildAt(last))) row.removeViewAt(last)
    }

    /** One further row: the bar's own gravity and no padding of its own — [rows] carries the bar's. */
    private fun newRow() = LinearLayout(bar.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** The width a view occupies in the bar: its exact `LayoutParams` width plus its margins. */
    private fun naturalWidth(view: View): Int {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
        val w = if (lp.width >= 0) lp.width else 0
        return w + lp.leftMargin + lp.rightMargin
    }

    /** True for the plain 1dp separators, false for every button. */
    private fun isDivider(view: View): Boolean = view.javaClass == View::class.java
}
