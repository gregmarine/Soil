package com.symmetricalpalmtree.soil.paper.chrome

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.soil.paper.R
import com.symmetricalpalmtree.soil.paper.core.InkTones

/**
 * **The shade panel**: the sixteen greys in four rows of four, hung under the armed pen button
 * on its re-tap. [EraserBar]'s shape in every particular that matters: [AnchoredBar] places it
 * and owns its rects; the screen owns when it opens and closes, and unions [rects] into the
 * exclusion rects, because a pen landing on a floating bar must never ink.
 *
 * A swatch is the grey itself as a filled circle with a hairline of black at its edge, a white
 * gap, and a **dotted** black outer ring; the armed swatch's ring is **solid**. Painted rather
 * than a drawable, because the selection has to read at every level, white included.
 *
 * **The bar stays open after a pick.** A visit is often "this grey, no, that one". It closes on
 * the pen's next re-tap, any tool change, a page swap, a finger gesture, a contact outside it, a
 * chrome flip and the exit: the screen's list.
 */
class PaletteBar(
    root: ViewGroup,
    bar: LinearLayout,
    private val anchor: View,
    bandBottom: () -> Int?,
    private val paper: PaperView,
    /** The level the panel is editing, read at every open and after every pick, never cached. */
    private val armedLevel: () -> Int,
    /** A pick: the screen arms the engine with its tone and remembers it. */
    private val onPicked: (level: Int) -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)
    private val swatches = ArrayList<Pair<Int, View>>()

    val isShowing: Boolean get() = this.bar.isShowing

    init {
        val ctx = root.context
        val cell = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        InkTones.rows().forEach { levels ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            levels.forEach { level ->
                val hint = when (level) {
                    InkTones.BLACK -> ctx.getString(R.string.cd_shade_black)
                    InkTones.WHITE -> ctx.getString(R.string.cd_shade_white)
                    else -> ctx.getString(R.string.cd_shade, level)
                }
                val swatch = Swatch(ctx, InkTones.tone(level)).apply {
                    layoutParams = LinearLayout.LayoutParams(cell, cell)
                    contentDescription = hint
                    TooltipCompat.setTooltipText(this, hint)
                    setOnClickListener { pick(level) }
                }
                row.addView(swatch)
                swatches += level to swatch
            }
            this.bar.addRow(row)
        }
    }

    /** Open under [anchor] with the armed level pressed. False before the root is laid out. */
    fun show(anchor: View = this.anchor): Boolean {
        paint(armedLevel())
        return bar.show(anchor)
    }

    fun hide() = bar.hide()

    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    private fun pick(level: Int) {
        PenIdle.releaseRenderIfIdle(paper)
        onPicked(level)
        paint(armedLevel())
    }

    /** `setSelected` invalidates on a change and does nothing on a repeat. */
    private fun paint(level: Int) {
        swatches.forEach { (l, view) -> view.isSelected = l == level }
    }

    private class Swatch(ctx: Context, private val ink: Int) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = ctx.resources.displayMetrics.density
        private val black = ContextCompat.getColor(ctx, R.color.inkBlack)
        private val dotted = DashPathEffect(floatArrayOf(DOT_DP * density, DOT_DP * density), 0f)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val ring = RING_DP * density
            val outer = minOf(width, height) / 2f - PAD_DP * density
            if (outer <= ring) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ring
            paint.color = black
            paint.pathEffect = if (isSelected) null else dotted
            canvas.drawCircle(cx, cy, outer - ring / 2f, paint)
            paint.pathEffect = null
            val fill = maxOf(0f, outer - ring - GAP_DP * density)
            paint.style = Paint.Style.FILL
            paint.color = ink
            canvas.drawCircle(cx, cy, fill, paint)
            val hair = HAIR_DP * density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = hair
            paint.color = black
            canvas.drawCircle(cx, cy, maxOf(0f, fill - hair / 2f), paint)
        }
    }

    private companion object {
        const val RING_DP = 2f
        const val PAD_DP = 6f
        const val GAP_DP = 3f
        const val DOT_DP = 2f
        const val HAIR_DP = 1f
    }
}
