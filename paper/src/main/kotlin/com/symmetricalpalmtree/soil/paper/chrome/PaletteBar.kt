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
 * **A size row under the shades** (the sketch face, Greg, 2026-10-08), when the screen passes
 * one: one cell per size on the armed kind's ladder, each a **sample of the stroke at its real
 * width** — a short horizontal line of [SizeOption.px], round-capped, black at the option's
 * alpha (the marker's row passes its translucency, so its samples read as the marker does) —
 * inside the same dotted-or-solid ring the shades wear, one selection vocabulary for the whole
 * panel. The words are the content description only. The ladder differs by kind and the bar is
 * built once, so the row is rebuilt at every open where the list has changed.
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
    /** The armed kind's size ladder, read at every open (it differs by kind), or null for a
     *  panel of shades alone — the writing faces'. */
    private val sizes: (() -> List<SizeOption>)? = null,
    /** The index the size row is editing, read at every open and after every pick. */
    private val armedSize: () -> Int = { 0 },
    /** A size pick: the screen arms the engine with its width and remembers it. */
    private val onSizePicked: (index: Int) -> Unit = {},
) {

    /** One size on the row: its width in page px, its words, the alpha its sample is drawn at
     *  (255 for an opaque tool, the marker's own for the marker), and a [badge] — a word or two
     *  drawn over the sample ("2x") to tell apart sizes too wide to differ inside the ring. */
    class SizeOption(val px: Float, val hint: String, val alpha: Int = 255, val badge: String? = null)

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)
    private val swatches = ArrayList<Pair<Int, View>>()
    /** The size rows' holder, vertical: the sizes wrap at [InkTones.ROW_BREAK] to a row, as the
     *  shades do (Greg, 2026-10-08: five across is too many). */
    private val sizeRows: LinearLayout?
    private val sizeSwatches = ArrayList<View>()
    private var builtSizes: List<SizeOption> = emptyList()
    private val cell: Int

    val isShowing: Boolean get() = this.bar.isShowing

    init {
        val ctx = root.context
        cell = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
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
        sizeRows = if (sizes == null) null else LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // As wide as the shade rows, so a row of three sits centred under four.
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            this@PaletteBar.bar.addRow(this)
        }
    }

    /** Open under [anchor] with the armed level and size pressed. False before the root is laid
     *  out. */
    fun show(anchor: View = this.anchor): Boolean {
        rebuildSizes()
        paint(armedLevel())
        paintSizes(armedSize())
        return bar.show(anchor)
    }

    /** The size row's cells, remade only when the ladder shown is not the one built. */
    private fun rebuildSizes() {
        val rows = sizeRows ?: return
        val wanted = sizes?.invoke() ?: return
        if (wanted.size == builtSizes.size && wanted.indices.all { wanted[it].px == builtSizes[it].px && wanted[it].alpha == builtSizes[it].alpha && wanted[it].hint == builtSizes[it].hint && wanted[it].badge == builtSizes[it].badge }) return
        rows.removeAllViews()
        sizeSwatches.clear()
        wanted.chunked(InkTones.ROW_BREAK).forEachIndexed { r, chunk ->
            val row = LinearLayout(rows.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            chunk.forEachIndexed { c, option ->
                val index = r * InkTones.ROW_BREAK + c
                val swatch = SizeSwatch(row.context, option.px, option.alpha, option.badge).apply {
                    layoutParams = LinearLayout.LayoutParams(cell, cell)
                    contentDescription = option.hint
                    TooltipCompat.setTooltipText(this, option.hint)
                    setOnClickListener { pickSize(index) }
                }
                row.addView(swatch)
                sizeSwatches += swatch
            }
            rows.addView(row)
        }
        builtSizes = wanted
    }

    private fun pickSize(index: Int) {
        PenIdle.releaseRenderIfIdle(paper)
        onSizePicked(index)
        paintSizes(armedSize())
    }

    private fun paintSizes(index: Int) {
        sizeSwatches.forEachIndexed { i, view -> view.isSelected = i == index }
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

    /** A stroke sample at its real width inside the shade swatch's ring: the width is page px,
     *  which on the Nomad is the glass's own, so the sample is the mark; the line is capped at
     *  the ring's inside for the broadest marker. */
    private class SizeSwatch(ctx: Context, private val px: Float, private val alpha: Int, private val badge: String?) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = BADGE_SP * ctx.resources.displayMetrics.scaledDensity
        }
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
            paint.alpha = 255
            paint.strokeCap = Paint.Cap.BUTT
            paint.pathEffect = if (isSelected) null else dotted
            canvas.drawCircle(cx, cy, outer - ring / 2f, paint)
            paint.pathEffect = null
            val inner = maxOf(0f, outer - ring - GAP_DP * density)
            // The broadest sample stops short of the ring, so the ring still reads around it.
            val stroke = px.coerceIn(1f, inner * 2f * SAMPLE_MAX)
            // The line's reach shrinks as it thickens, so a broad sample's round caps stay
            // inside the ring.
            val half = maxOf(0f, inner - stroke / 2f)
            paint.strokeWidth = stroke
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = black
            paint.alpha = alpha
            canvas.drawLine(cx - half, cy, cx + half, cy, paint)
            // The badge over the sample, black on it: a word tells apart what the ring cannot.
            badge?.let {
                text.color = black
                canvas.drawText(it, cx, cy - (text.descent() + text.ascent()) / 2f, text)
            }
        }
    }

    private companion object {
        const val RING_DP = 2f
        const val SAMPLE_MAX = 0.8f
        const val BADGE_SP = 13f
        const val PAD_DP = 6f
        const val GAP_DP = 3f
        const val DOT_DP = 2f
        const val HAIR_DP = 1f
    }
}
