package com.symmetricalpalmtree.soil.docsprout.editor.rich

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import com.symmetricalpalmtree.soil.markdown.HeadingTypography
import com.symmetricalpalmtree.soil.markdown.rich.RichAttr
import com.symmetricalpalmtree.soil.markdown.rich.RichKind

/**
 * The sizes a rendered document is drawn at, every one a fraction of the body text's size, so a
 * change of text size changes all of them together.
 */
class BlockMetrics(var em: Float, density: Float) {
    /** One level of list indent, and the room a marker is drawn in. */
    val step: Int get() = (em * 1.5f).toInt()
    val quoteIndent: Int get() = em.toInt()
    /** Never under 2 px: a thinner stripe disappears on e-ink. */
    val stripe: Float get() = maxOf(2f, em * 0.16f)
    /** The air above a block, and above one that sits tight under the one before. */
    val gap: Int get() = (em * 0.6f).toInt()
    val tightGap: Int get() = (em * 0.15f).toInt()
    val hairline: Float = maxOf(1f, density)
}

/**
 * **One block of a rendered document**, as one span over its paragraph, line break included:
 * what the block is ([attr]), and everything that draws it as that. A heading's size and weight
 * and a raw line's typeface (it is a character style), the indent and what stands in it: a
 * bullet, a number, a task's box, a quote's stripe, the rule itself (a leading margin), and the
 * air above the block (a line height).
 *
 * Nothing of a block's kind is ever a character in the text, so no marker can be typed into,
 * selected or half-deleted. [number] and [gapTop] depend on the blocks before this one and are
 * set by the editor's pass over the document.
 */
class BlockSpan(var attr: RichAttr, val metrics: BlockMetrics) : MetricAffectingSpan(), LeadingMarginSpan, LineHeightSpan {

    var number: Int = 0
    var gapTop: Int = 0

    // ── The words ──────

    override fun updateMeasureState(paint: TextPaint) = style(paint)

    override fun updateDrawState(paint: TextPaint) {
        style(paint)
        // A rule's one character stands in for it and is never seen: the rule is drawn in the margin.
        if (attr.kind == RichKind.RULE) paint.alpha = 0
    }

    private fun style(paint: TextPaint) {
        when (attr.kind) {
            RichKind.HEADING -> {
                paint.textSize = paint.textSize * HeadingTypography.scaleFor(attr.level)
                // Whatever the run already is (an italic word in a heading), and bold.
                val old = paint.typeface
                paint.typeface = Typeface.create(old, (old?.style ?: Typeface.NORMAL) or Typeface.BOLD)
            }
            RichKind.RAW -> paint.typeface = Typeface.MONOSPACE
            // Its stand-in character takes no room, as near as a paint can make it.
            RichKind.RULE -> paint.textScaleX = RULE_SCALE_X
            else -> Unit
        }
    }

    // ── The air above ──────

    private var raisedAscent = 0
    private var raisedTop = 0
    private var raisedBy = 0

    /**
     * The air goes above the block's first line only. A layout may hand the next line the very
     * metrics this left on the first; when a later line arrives still carrying them, they are
     * put back, so the air is not given again between the block's own lines.
     */
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (isFirstLine(text, start)) {
            if (gapTop > 0) {
                fm.ascent -= gapTop
                fm.top -= gapTop
            }
            raisedAscent = fm.ascent
            raisedTop = fm.top
            raisedBy = gapTop
        } else if (raisedBy > 0 && fm.ascent == raisedAscent && fm.top == raisedTop) {
            fm.ascent += raisedBy
            fm.top += raisedBy
        }
    }

    private fun isFirstLine(text: CharSequence, lineStart: Int): Boolean = (text as? Spanned)?.getSpanStart(this) == lineStart

    // ── The margin, and what stands in it ──────

    override fun getLeadingMargin(first: Boolean): Int = margin()

    fun margin(): Int = when {
        attr.isList -> (attr.depth + 1) * metrics.step
        attr.kind == RichKind.QUOTE -> metrics.quoteIndent
        else -> 0
    }

    override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?) {
        val firstLine = isFirstLine(text, start)
        val kind = attr.kind
        if (!firstLine && kind != RichKind.QUOTE) return
        // The paint is the layout's: every field touched is put back.
        val style = p.style
        val color = p.color
        val stroke = p.strokeWidth
        val align = p.textAlign
        p.color = Color.BLACK
        val em = metrics.em
        val edge = x + dir * margin()
        when (kind) {
            RichKind.QUOTE -> {
                p.style = Paint.Style.FILL
                val from = (top + if (firstLine) gapTop else 0).toFloat()
                c.drawRect(minOf(x.toFloat(), x + dir * metrics.stripe), from, maxOf(x.toFloat(), x + dir * metrics.stripe), bottom.toFloat(), p)
            }
            RichKind.RULE -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = metrics.hairline
                val y = (top + gapTop + bottom) / 2f
                c.drawLine(x.toFloat(), y, (layout?.width ?: c.width).toFloat(), y, p)
            }
            RichKind.BULLET -> {
                val cx = edge - dir * metrics.step * 0.5f
                val cy = baseline - em * 0.33f
                val r = em * 0.13f
                when (attr.depth % 3) {
                    0 -> { p.style = Paint.Style.FILL; c.drawCircle(cx, cy, r, p) }
                    1 -> { p.style = Paint.Style.STROKE; p.strokeWidth = metrics.hairline; c.drawCircle(cx, cy, r, p) }
                    else -> { p.style = Paint.Style.FILL; c.drawRect(cx - r, cy - r, cx + r, cy + r, p) }
                }
            }
            RichKind.ORDERED -> {
                p.style = Paint.Style.FILL
                p.textAlign = if (dir > 0) Paint.Align.RIGHT else Paint.Align.LEFT
                c.drawText("$number.", edge - dir * em * 0.35f, baseline.toFloat(), p)
            }
            RichKind.TASK -> {
                val size = em * 0.74f
                val left = if (dir > 0) edge - metrics.step + (metrics.step - size) / 2f - em * 0.1f else edge + (metrics.step - size) / 2f + em * 0.1f
                val boxBottom = baseline + em * 0.08f
                val boxTop = boxBottom - size
                p.style = Paint.Style.STROKE
                p.strokeWidth = metrics.hairline * 1.5f
                c.drawRect(left, boxTop, left + size, boxBottom, p)
                if (attr.checked) {
                    p.strokeWidth = metrics.hairline * 2f
                    c.drawLine(left + size * 0.2f, boxTop + size * 0.55f, left + size * 0.42f, boxTop + size * 0.78f, p)
                    c.drawLine(left + size * 0.42f, boxTop + size * 0.78f, left + size * 0.82f, boxTop + size * 0.24f, p)
                }
            }
            else -> Unit
        }
        p.style = style
        p.color = color
        p.strokeWidth = stroke
        p.textAlign = align
    }
}

/** How narrow a rule's stand-in character is drawn: as near nothing as a paint will take. */
private const val RULE_SCALE_X = 0.01f
