package com.symmetricalpalmtree.soil.paper.chrome

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.soil.paper.R

/**
 * The floating selection bar an **ink-on-paper extension screen** puts next to a lasso selection
 * (arc 11 / J4 as the pad's own, the calendar's copy at arc 23 / Y1, **one class here** since the
 * arc-23 sweep): **Send to Notebook** when a notebook is behind us, **Delete** last.
 *
 * The shape is the shared decision, and it is why this is not simply a [FloatingSelectionBar] call
 * at each consumer: Move is the drag itself and neither screen has headings, links or snap, so the
 * notebook's buttons come down to the one that always applied plus the one that puts the ink on
 * the clipboard — **Copy first, Delete last**, the notebook's order, with the one destructive verb
 * on the far edge. The sticky editor, which drags as the notebook does, passes a [SnapToggle] and
 * its **Snap** leads, as on the notebook's bar (SN's: Snap · Copy · Cut · Delete). Both release the render before their row runs: the tap has to
 * show its result, and the delete repaints the page underneath.
 *
 * Copy is **absent, never disabled**, when the screen offers none — a greyed control is invisible
 * on e-ink. That is what a null [copyHint] means; the hint itself is the consumer's own wording,
 * which is all that ever differed between the two copies. Its glyph is the copy icon, the one the
 * notebook's own Copy wears (Greg, 2026-10-06): the pad's and the calendar's Copy are copies, not
 * a Send.
 */
class InkSelectionBar(
    root: ViewGroup,
    paperView: View,
    bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom bar's top. */
    band: () -> IntRange?,
    releaseRender: () -> Unit,
    /** Delete's hint (tooltip + content description). */
    deleteHint: String,
    onDelete: () -> Unit,
    /** Copy's hint, or **null** when the screen offers no Copy — then the button is never built. */
    copyHint: String? = null,
    onCopy: () -> Unit = {},
    /** Snap to guides, leading the bar, or **null** on a screen that does not snap. */
    private val snap: SnapToggle? = null,
) {

    private val floating = FloatingSelectionBar(
        root = root,
        paperView = paperView,
        bar = bar,
        band = band,
        buttons = buildList {
            if (snap != null) {
                add(FloatingSelectionBar.Button(R.drawable.ic_snap, root.context.getString(R.string.snap_action_off)) {
                    releaseRender(); snap.toggle(); syncSnap()
                })
            }
            if (copyHint != null) {
                add(FloatingSelectionBar.Button(R.drawable.ic_copy, copyHint) {
                    releaseRender(); onCopy()
                })
            }
            add(FloatingSelectionBar.Button(R.drawable.ic_trash, deleteHint) {
                releaseRender(); onDelete()
            })
        },
    )

    fun show(bounds: Bounds) {
        syncSnap()
        floating.show(bounds)
    }

    private fun syncSnap() {
        snap?.sync(floating.buttonAt(0))
    }

    fun hide() = floating.hide()
    fun rects(): List<Rect> = floating.rects()
    fun contains(x: Int, y: Int): Boolean = floating.contains(x, y)
}
