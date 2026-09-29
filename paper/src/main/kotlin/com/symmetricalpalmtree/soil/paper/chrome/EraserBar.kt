package com.symmetricalpalmtree.soil.paper.chrome

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.soil.paper.R

/**
 * The eraser button's sub-bar (arc 29 / LE2, D3) — a small bordered bar hung under the **armed**
 * eraser button, holding the two erasers:
 *
 *  **Point** (`Tool.ERASER`, the 15 px whole-stroke eraser) · **Lasso** (`Tool.LASSO_ERASER`,
 *  g-paper 0.1.28 — a closed outline takes everything it holds, on the lasso's own hit rule)
 *
 * left to right, in that order, always. Placement, the button recipe and the rects are
 * [AnchoredBar]'s — the arc-8 lasso popup's bar, the arc-21 tag bar's and the arc-28 Insert bar's,
 * and one shape has one implementation.
 *
 * **It lives here, in `:sn-screen`, because all four paper surfaces get it** — the notebook and the
 * sticky editor (LE2), the scratch pad and the calendar (LE3). A second copy would be the
 * `RattaNotebookView` sibling-copy trap one file at a time, which is exactly what this module
 * exists to prevent.
 *
 * **The bar remembers nothing.** A plain tap on the eraser button from another tool always arms
 * [Tool.ERASER]; the lasso eraser is reached only through a second tap on the already-armed eraser,
 * which is what opens this bar. [show] paints the armed one `isSelected` so the bar is honest the
 * moment it opens, and nothing here is carried between openings or between sessions (P1).
 *
 * The screen owns *when* it closes — a pick, a tool tap, another bar's button, a page swap, a
 * finger gesture, an outside tap — and unions [rects] into the exclusion rects and the `overChrome`
 * test, because a pen landing on a floating bar must never ink.
 */
class EraserBar(
    root: ViewGroup,
    bar: LinearLayout,
    /** The armed eraser top-bar button the sub-bar hangs under. */
    anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    bandBottom: () -> Int?,
    private val paper: PaperView,
    /** Fires after the tool is armed — the screen hides the bar and syncs its toolbar. */
    private val onPicked: (Tool) -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    private val btnPoint: AppCompatImageButton
    private val btnLasso: AppCompatImageButton

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        btnPoint = this.bar.addButton(R.drawable.ic_eraser, ctx.getString(R.string.eraser_point)) {
            pick(Tool.ERASER)
        }
        btnLasso = this.bar.addButton(R.drawable.ic_lasso_eraser, ctx.getString(R.string.eraser_lasso)) {
            pick(Tool.LASSO_ERASER)
        }
    }

    /**
     * Arm one of the two. The render release is the toolbar's own rule — pen-gated, because an
     * ungated release inside the pen-active window can cost a live stroke — and the assignment is
     * skipped when the tool is already armed, so picking the armed one is honestly a no-op on the
     * surface. [onPicked] still fires: the screen has a bar to take down and buttons to sync, and
     * a host-set tool is never echoed back as `onToolChanged`.
     */
    private fun pick(tool: Tool) {
        PenIdle.releaseRenderIfIdle(paper)
        if (paper.tool != tool) paper.tool = tool
        onPicked(tool)
    }

    /**
     * Open the bar under the anchor, with the armed eraser already pressed. Returns false — showing
     * nothing — before the root has been laid out ([AnchoredBar.show]'s rule), which is what keeps a
     * caller's toggle honest at every moment the geometry is not yet knowable.
     */
    fun show(): Boolean {
        btnPoint.isSelected = paper.tool == Tool.ERASER
        btnLasso.isSelected = paper.tool == Tool.LASSO_ERASER
        return bar.show()
    }

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)
}
