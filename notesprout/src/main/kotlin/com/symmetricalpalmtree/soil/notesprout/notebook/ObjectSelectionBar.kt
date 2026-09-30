package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.objects.SelectionMode
import com.symmetricalpalmtree.soil.paper.chrome.AnchoredBar
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.SelectionAnchor
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * The lasso's bar over the notebook: what can be done with what was caught. The buttons that do
 * not apply to a selection are absent, never greyed.
 *
 * - **H** on a lone heading: the H1–H6 sub-bar picks its level. (On ink it will convert the ink,
 *   once recognition arrives.)
 * - **Delete** on anything.
 */
class ObjectSelectionBar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    private val subBar: LinearLayout,
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val onLevelPicked: (Int) -> Unit,
    private val onDelete: () -> Unit,
) {
    private val density = root.resources.displayMetrics.density
    private val headingButton: AppCompatImageButton
    private val levelButtons: List<AppCompatImageButton>
    private var barPlacement: SelectionAnchor.Placement? = null

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        headingButton = button(com.symmetricalpalmtree.soil.paper.R.drawable.ic_heading, ctx.getString(R.string.selection_heading)) { toggleLevels() }
        bar.addView(headingButton)
        bar.addView(button(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, ctx.getString(R.string.delete_selection_action)) { onDelete() })
        val icons = listOf(
            com.symmetricalpalmtree.soil.paper.R.drawable.ic_h_1, com.symmetricalpalmtree.soil.paper.R.drawable.ic_h_2,
            com.symmetricalpalmtree.soil.paper.R.drawable.ic_h_3, com.symmetricalpalmtree.soil.paper.R.drawable.ic_h_4,
            com.symmetricalpalmtree.soil.paper.R.drawable.ic_h_5, com.symmetricalpalmtree.soil.paper.R.drawable.ic_h_6,
        )
        levelButtons = icons.mapIndexed { i, res ->
            button(res, ctx.getString(R.string.selection_level, i + 1)) { subBar.visibility = View.GONE; onLevelPicked(i + 1) }.also { subBar.addView(it) }
        }
    }

    fun show(bounds: Bounds, mode: SelectionMode, currentLevel: Int?) {
        val band = band() ?: return
        headingButton.visibility = if (mode == SelectionMode.HEADING) View.VISIBLE else View.GONE
        subBar.visibility = View.GONE
        levelButtons.forEachIndexed { i, b -> b.isSelected = (i + 1) == currentLevel }

        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val dx = paperLoc[0] - rootLoc[0]
        val dy = paperLoc[1] - rootLoc[1]
        val box = bounds.inflated(SELECTION_BOX_INFLATE_PX)
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = SelectionAnchor.place(
            selLeft = (box.left + dx).toInt(), selTop = (box.top + dy).toInt(),
            selRight = (box.right + dx).toInt(), selBottom = (box.bottom + dy).toInt(),
            toolbarW = bar.measuredWidth, toolbarH = bar.measuredHeight,
            gap = (GAP_DP * density).toInt(), rootWidth = root.width,
            bandTop = band.first, bandBottom = band.last,
        )
        barPlacement = p
        place(bar, p.x, p.y)
        Slog.d(TAG) { "shown for $mode" }
    }

    fun hide() {
        bar.visibility = View.GONE
        subBar.visibility = View.GONE
        barPlacement = null
    }

    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(bar), PaperToolbar.rectOf(subBar))
    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    private fun toggleLevels() {
        if (subBar.visibility == View.VISIBLE) { subBar.visibility = View.GONE; return }
        val p = barPlacement ?: return
        val band = band() ?: return
        subBar.visibility = View.VISIBLE
        subBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val q = SelectionAnchor.placeSub(
            bar = p,
            barW = bar.measuredWidth.takeIf { it > 0 } ?: bar.width,
            barH = bar.measuredHeight.takeIf { it > 0 } ?: bar.height,
            w = subBar.measuredWidth, h = subBar.measuredHeight,
            gap = (GAP_DP * density).toInt(), rootWidth = root.width,
            bandTop = band.first, bandBottom = band.last,
        )
        place(subBar, q.x, q.y)
    }

    private fun place(v: View, x: Int, y: Int) {
        val lp = (v.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x
        lp.topMargin = y
        v.layoutParams = lp
    }

    private fun button(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton =
        AnchoredBar.button(bar.context, iconRes, hint) { releaseRender(); onClick() }

    private companion object {
        const val TAG = "ObjectSelectionBar"
        const val GAP_DP = 8f
        /** g-paper's own box inflation, mirrored. */
        const val SELECTION_BOX_INFLATE_PX = 12f
    }
}
