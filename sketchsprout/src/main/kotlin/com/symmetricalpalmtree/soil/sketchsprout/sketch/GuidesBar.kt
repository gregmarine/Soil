package com.symmetricalpalmtree.soil.sketchsprout.sketch

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.soil.paper.chrome.AnchoredBar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.sketchsprout.R
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * The **guides panel** — one anchored panel under the top bar's Guides button, the shade panel's
 * recipe over `:paper`'s [AnchoredBar]. Rows: **Grid** (Off · Lines · Dots latches, the grid's
 * Show/Hide eye), the ten **counts** in two rows (only while a grid is on), **Reference** (Pick,
 * Remove, the image's eye), the four **opacities** (only while the page carries an image).
 *
 * **Latches, never steppers or sliders**: exactly one of a row is down. A control that means
 * nothing yet is **`GONE`, never disabled** — a disabled button is invisible on e-ink. When a
 * pick changes which rows show, the bar is re-placed under its anchor. It stays open after a
 * pick, and closes on the screen's list: the Guides re-tap, a tool change, a page swap, a finger
 * gesture, a chrome flip, a contact outside it, the exit.
 */
class GuidesBar(
    root: ViewGroup,
    bar: LinearLayout,
    private val anchor: View,
    bandBottom: () -> Int?,
    private val paper: PaperView,
    private val state: () -> GuideState,
    private val onChanged: (GuideState) -> Unit,
    private val onPickImage: () -> Unit,
    private val onRemoveImage: () -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)
    private val ctx: Context = root.context
    private var shownUnder: View = anchor

    private val kindLatches = ArrayList<Pair<GuideState.Kind, Button>>()
    private val countLatches = ArrayList<Pair<Int, Button>>()
    private val opacityLatches = ArrayList<Pair<Int, Button>>()
    private val countRows: List<LinearLayout>
    private val opacityRow: LinearLayout
    private val gridEye: AppCompatImageButton
    private val imageEye: AppCompatImageButton
    private val removeButton: AppCompatImageButton

    val isShowing: Boolean get() = this.bar.isShowing

    init {
        this.bar.addRow(header(R.string.guides_grid))
        val kindRow = newRow()
        listOf(
            GuideState.Kind.OFF to R.string.guides_grid_off,
            GuideState.Kind.LINES to R.string.guides_grid_lines,
            GuideState.Kind.DOTS to R.string.guides_grid_dots,
        ).forEach { (kind, label) ->
            val latch = latch(ctx.getString(label)) { pick { it.withGrid(kind) } }
            kindRow.addView(latch)
            kindLatches += kind to latch
        }
        gridEye = AnchoredBar.button(ctx, PaperR.drawable.ic_eye, ctx.getString(R.string.guides_hide_grid)) { pick { it.toggleGridVisible() } }
        kindRow.addView(gridEye)
        this.bar.addRow(kindRow)

        countRows = GuideSheet.countRows().map { counts ->
            val row = newRow()
            counts.forEach { count ->
                val latch = latch(count.toString()) { pick { it.withCount(count) } }
                row.addView(latch)
                countLatches += count to latch
            }
            this.bar.addRow(row)
            row
        }

        this.bar.addRow(header(R.string.guides_reference))
        val imageRow = newRow()
        imageRow.addView(AnchoredBar.button(ctx, PaperR.drawable.ic_photo_plus, ctx.getString(R.string.guides_pick_image)) { PenIdle.releaseRenderIfIdle(paper); onPickImage() })
        removeButton = AnchoredBar.button(ctx, PaperR.drawable.ic_trash, ctx.getString(R.string.guides_remove_image)) { PenIdle.releaseRenderIfIdle(paper); onRemoveImage() }
        imageRow.addView(removeButton)
        imageEye = AnchoredBar.button(ctx, PaperR.drawable.ic_eye, ctx.getString(R.string.guides_hide_image)) { pick { it.toggleImageVisible() } }
        imageRow.addView(imageEye)
        this.bar.addRow(imageRow)

        opacityRow = newRow()
        GuideSheet.OPACITIES.forEach { percent ->
            val latch = latch(ctx.getString(R.string.guides_opacity, percent)) { pick { it.withOpacity(percent) } }
            opacityRow.addView(latch)
            opacityLatches += percent to latch
        }
        this.bar.addRow(opacityRow)
    }

    /** Open under [anchor], painted from the page's state. False before the root is laid out. */
    fun show(anchor: View = this.anchor): Boolean {
        paint(state())
        shownUnder = anchor
        return bar.show(anchor)
    }

    fun hide() = bar.hide()
    fun rects(): List<Rect> = bar.rects()
    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    /** Repaint from the screen's state; re-places the bar when the rows it shows changed. */
    fun refresh() {
        if (!isShowing) return
        if (paint(state())) bar.show(shownUnder)
    }

    private fun pick(edit: (GuideState) -> GuideState) {
        PenIdle.releaseRenderIfIdle(paper)
        onChanged(edit(state()))
        refresh()
    }

    private fun paint(s: GuideState): Boolean {
        kindLatches.forEach { (kind, b) -> b.isSelected = kind == s.gridKind }
        countLatches.forEach { (count, b) -> b.isSelected = count == s.gridCount }
        opacityLatches.forEach { (percent, b) -> b.isSelected = percent == s.imageOpacity }
        eye(gridEye, s.gridVisible, R.string.guides_hide_grid, R.string.guides_show_grid)
        eye(imageEye, s.imageVisible, R.string.guides_hide_image, R.string.guides_show_image)
        var moved = false
        moved = setShown(gridEye, s.gridOn) || moved
        for (row in countRows) moved = setShown(row, s.gridOn) || moved
        moved = setShown(removeButton, s.hasImage) || moved
        moved = setShown(imageEye, s.hasImage) || moved
        moved = setShown(opacityRow, s.hasImage) || moved
        return moved
    }

    private fun eye(button: AppCompatImageButton, visible: Boolean, hideRes: Int, showRes: Int) {
        val icon = if (visible) PaperR.drawable.ic_eye else PaperR.drawable.ic_eye_off
        if (button.tag != icon) { button.tag = icon; button.setImageResource(icon) }
        val hint = ctx.getString(if (visible) hideRes else showRes)
        if (button.contentDescription != hint) { button.contentDescription = hint; TooltipCompat.setTooltipText(button, hint) }
    }

    private fun setShown(view: View, shown: Boolean): Boolean {
        val want = if (shown) View.VISIBLE else View.GONE
        if (view.visibility == want) return false
        view.visibility = want
        return true
    }

    private fun latch(label: String, onClick: () -> Unit): Button {
        val size = ctx.resources.getDimensionPixelSize(PaperR.dimen.toolbar_button_size)
        val gap = (GAP_DP * ctx.resources.displayMetrics.density).toInt()
        return Button(ctx, null, 0, PaperR.style.Widget_Soil_LatchButton).apply {
            text = label
            minWidth = size; minimumWidth = size
            minHeight = 0; minimumHeight = 0
            gravity = Gravity.CENTER
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size).apply { setMargins(gap, gap, gap, gap) }
            setOnClickListener { onClick() }
        }
    }

    private fun header(res: Int): TextView = TextView(ctx).apply {
        text = ctx.getString(res)
        setTextColor(ContextCompat.getColor(ctx, PaperR.color.inkBlack))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP)
        val pad = (GAP_DP * ctx.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun newRow(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private companion object {
        const val GAP_DP = 3f
        const val HEADER_SP = 13f
    }
}
