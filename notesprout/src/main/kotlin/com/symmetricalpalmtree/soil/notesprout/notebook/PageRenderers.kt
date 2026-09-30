package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextPaint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import com.symmetricalpalmtree.soil.markdown.HeadingTypography
import com.symmetricalpalmtree.soil.markdown.MarkdownDraw
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The renderers that paint the page's objects into g-paper's committed layer: headings and texts
 * below the ink, so an annotation over them stays visible; sticky notes above it. Each holds the screen's working copy, set on
 * Main; the engine re-records on `notifyContentChanged()`, never per frame. Each implements the
 * live-drag pair, so a dragged object rides under the pen as its real self.
 *
 * Draw order is the caller's, by registration: headings · texts · stickies, then the ink.
 */

/** Headings: the stored hash prefix goes through the Markdown engine, single line, ellipsized. The
 *  box was measured by [measure] with the same pipeline, so pixels and bounds cannot disagree. */
class HeadingRenderer(private val density: Float, scaledDensity: Float) : ContentRenderer {
    override val layer = ContentLayer.BELOW_STROKES
    var headings: List<Heading> = emptyList()
    private val paint = basePaint(scaledDensity)

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())
    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (h in headings) if (h.id !in excludedContentIds) drawHeading(canvas, h, density, paint)
    }
    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val h = headings.firstOrNull { it.id == contentId } ?: return false
        drawHeading(canvas, h, density, paint)
        return true
    }
    override fun hitTargets(): List<HitTarget> = headings.map { HitTarget(it.id, it.bounds) }

    companion object {
        fun drawHeading(canvas: Canvas, h: Heading, density: Float, paint: TextPaint) {
            val pad = HeadingTypography.paddingPx(density)
            val contentWidth = (h.width - 2 * pad).roundToInt()
            if (contentWidth <= 0) return
            MarkdownDraw.draw(canvas, h.text, x = h.x + pad, y = h.y + pad, widthPx = contentWidth, paint = paint, density = density, maxLines = 1)
        }

        /** The box for [text]: its natural single-line size plus padding, in page px. Free growth:
         *  a title never wraps, and an overhang past the page edge is simply not visible. */
        fun measure(text: String, density: Float, scaledDensity: Float): Pair<Float, Float> {
            val pad = HeadingTypography.paddingPx(density)
            val (w, h) = MarkdownDraw.measure(text, FREE_GROWTH_WIDTH_PX, basePaint(scaledDensity), density, singleLine = true)
            return (w + 2 * pad) to (h + 2 * pad)
        }

        fun basePaint(scaledDensity: Float) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = HeadingTypography.BASE_SP * scaledDensity
        }

        private const val FREE_GROWTH_WIDTH_PX = 1_000_000
    }
}

/** Text objects: the raw Markdown through the engine at [BASE_SP], wrapped at the stored width. */
class TextRenderer(private val density: Float, scaledDensity: Float) : ContentRenderer {
    override val layer = ContentLayer.BELOW_STROKES
    var texts: List<PageText> = emptyList()
    private val paint = basePaint(scaledDensity)

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())
    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (t in texts) if (t.id !in excludedContentIds) drawText(canvas, t, density, paint)
    }
    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val t = texts.firstOrNull { it.id == contentId } ?: return false
        drawText(canvas, t, density, paint)
        return true
    }
    override fun hitTargets(): List<HitTarget> = texts.map { HitTarget(it.id, it.bounds) }

    companion object {
        const val BASE_SP = 24f

        fun drawText(canvas: Canvas, t: PageText, density: Float, paint: TextPaint) {
            val w = t.width.roundToInt()
            if (w <= 0) return
            MarkdownDraw.draw(canvas, t.text, x = t.x, y = t.y, widthPx = w, paint = paint, density = density)
        }

        /** The one sizing function: the natural multi-line size wrapped at [availableWidthPx], which
         *  is `pageWidth − x`, never the page width. */
        fun measure(text: String, availableWidthPx: Int, density: Float, scaledDensity: Float): Pair<Float, Float> {
            val avail = max(availableWidthPx, MIN_WIDTH_PX)
            val (w, h) = MarkdownDraw.measure(text, avail, basePaint(scaledDensity), density)
            return w.toFloat() to h.toFloat()
        }

        fun basePaint(scaledDensity: Float) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BASE_SP * scaledDensity
        }

        private const val MIN_WIDTH_PX = 48
    }
}

/** Sticky notes: the icon scaled into its box, **above the ink**: a note dropped on a page sits
 *  on top of everything, so nothing shows through it. The content never draws on the page. */
class StickyRenderer(private val icon: Drawable) : ContentRenderer {
    override val layer = ContentLayer.ABOVE_STROKES
    var stickies: List<PageSticky> = emptyList()

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())
    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (s in stickies) if (s.id !in excludedContentIds) drawSticky(canvas, s, icon)
    }
    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val s = stickies.firstOrNull { it.id == contentId } ?: return false
        drawSticky(canvas, s, icon)
        return true
    }
    override fun hitTargets(): List<HitTarget> = stickies.map { HitTarget(it.id, it.bounds) }

    companion object {
        fun drawSticky(canvas: Canvas, s: PageSticky, icon: Drawable) {
            if (s.width <= 0f || s.height <= 0f) return
            icon.setBounds(s.x.roundToInt(), s.y.roundToInt(), (s.x + s.width).roundToInt(), (s.y + s.height).roundToInt())
            icon.draw(canvas)
        }
    }
}
