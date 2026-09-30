package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.objects.PageShape
import com.symmetricalpalmtree.soil.notesprout.objects.ShapeGeometry
import com.symmetricalpalmtree.soil.notesprout.objects.ShapeType
import com.symmetricalpalmtree.soil.paper.chrome.AnchoredBar
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.SelectionAnchor

/**
 * The floating bar beside a shape while the engine's transform mode has it: the **aspect latch**,
 * a word rather than a glyph ("Circle" and "Oval" are the two things the shape can be), and
 * **Done**. Every other way out of the mode belongs to the engine, and all of them tear this bar
 * down through the same `onTransformEnded`.
 *
 * It is placed off the shape's box grown by the overlay's reach (the rotate knob's stem, the knob,
 * and the finger's pad), and moves as little as possible: only when the growing shape reaches it.
 */
class ShapeTransformBar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val onToggleLock: () -> Unit,
    private val onDone: () -> Unit,
) {
    private val density = root.resources.displayMetrics.density
    private val lockButton: AppCompatButton

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        val size = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_button_size)
        val padH = (LABEL_PAD_DP * density).toInt()
        lockButton = AppCompatButton(ctx).apply {
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            setTextColor(ContextCompat.getColor(ctx, com.symmetricalpalmtree.soil.paper.R.color.inkBlack))
            textSize = LABEL_SP
            isAllCaps = false
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding(padH, 0, padH, 0)
            val hint = ctx.getString(R.string.shape_lock_hint)
            contentDescription = hint
            TooltipCompat.setTooltipText(this, hint)
            setOnClickListener { releaseRender(); onToggleLock() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
        }
        bar.addView(lockButton)
        bar.addView(AnchoredBar.button(ctx, com.symmetricalpalmtree.soil.paper.R.drawable.ic_check, ctx.getString(R.string.shape_transform_done)) { releaseRender(); onDone() })
    }

    fun show(shape: PageShape) {
        val band = band() ?: return
        relabel(shape)
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val dx = paperLoc[0] - rootLoc[0]
        val dy = paperLoc[1] - rootLoc[1]
        val box = overlayBox(shape)
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = SelectionAnchor.place(
            selLeft = (box.left + dx).toInt(), selTop = (box.top + dy).toInt(),
            selRight = (box.right + dx).toInt(), selBottom = (box.bottom + dy).toInt(),
            toolbarW = bar.measuredWidth, toolbarH = bar.measuredHeight,
            gap = (GAP_DP * density).toInt(), rootWidth = root.width,
            bandTop = band.first, bandBottom = band.last,
        )
        val lp = (bar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = p.x
        lp.topMargin = p.y
        bar.layoutParams = lp
    }

    /** The latch names the shape's **current** state, the way the armed tool reads. */
    fun relabel(shape: PageShape) {
        lockButton.setText(labelRes(shape.type, shape.aspectLocked))
        lockButton.isSelected = shape.aspectLocked
    }

    /** Whether the overlay for [shape] now overlaps the bar where it stands. */
    fun coveredBy(shape: PageShape): Boolean {
        val barRect = PaperToolbar.rectOf(bar) ?: return false
        val loc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val b = overlayBox(shape)
        return Rect.intersects(barRect, Rect((b.left + loc[0]).toInt(), (b.top + loc[1]).toInt(), (b.right + loc[0]).toInt(), (b.bottom + loc[1]).toInt()))
    }

    fun hide() { bar.visibility = View.GONE }
    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(bar))
    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    private fun overlayBox(shape: PageShape): Bounds =
        ShapeGeometry.aabb(shape, density).inflated(SELECTION_BOX_INFLATE_PX + OVERLAY_REACH_DP * density)

    companion object {
        /** What the latch says: ellipse Circle/Oval, rectangle Square/Rect, the rest 1:1/Free. */
        fun labelRes(type: ShapeType, locked: Boolean): Int = when (type) {
            ShapeType.ELLIPSE -> if (locked) R.string.shape_lock_circle else R.string.shape_lock_oval
            ShapeType.RECTANGLE -> if (locked) R.string.shape_lock_square else R.string.shape_lock_rect
            ShapeType.TRIANGLE, ShapeType.STAR, ShapeType.LINE, ShapeType.ARROW -> if (locked) R.string.shape_lock_1_1 else R.string.shape_lock_free
        }

        private const val GAP_DP = 8f
        private const val OVERLAY_REACH_DP = 36f + 14f + 22f
        private const val SELECTION_BOX_INFLATE_PX = 12f
        private const val LABEL_SP = 14f
        private const val LABEL_PAD_DP = 12f
    }
}
