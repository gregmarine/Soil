package com.symmetricalpalmtree.soil.docsprout.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.hardware.display.DisplayManager
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import com.symmetricalpalmtree.soil.docsprout.editor.rich.BlockMetrics
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichCodec
import com.symmetricalpalmtree.soil.markdown.MarkdownPaginator
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import com.symmetricalpalmtree.soil.seam.Seam
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * The page a document is laid out on for an export, in the layout's own units: PDF points for a
 * paper size, the screen's pixels for the screen. [pixelsPerUnit] is what a page picture is drawn
 * at and [pointsPerUnit] what a PDF page is, so one layout gives the same pages as pictures and
 * as a PDF of text.
 */
class PageSpec(
    val width: Int,
    val height: Int,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    /** The body text's size, in units. */
    val em: Float,
    val density: Float,
    val pixelsPerUnit: Float,
    val pointsPerUnit: Float,
) {
    val contentWidth: Int get() = width - marginLeft - marginRight
    val contentHeight: Int get() = height - marginTop - marginBottom
    val widthPx: Int get() = (width * pixelsPerUnit).roundToInt()
    val heightPx: Int get() = (height * pixelsPerUnit).roundToInt()

    companion object {
        /** Three quarters of an inch all round, on paper. */
        private const val PAPER_MARGIN_PT = 54

        /**
         * The editor's text size as type on paper: the five sizes (shown two sp larger in the
         * rendered editor) print at about 10, 11, 12.5, 14 and 17 pt.
         */
        private const val PAPER_POINTS_PER_SP = 0.62f

        /** The rendered editor shows prose two sp larger than the size chosen; so does a page. */
        private const val RENDERED_BUMP_SP = 2f

        /**
         * The page for [pageSize] (one of `Seam.PAGE_*`; anything else is the screen), with body
         * text at the editor's [textSizeSp].
         */
        fun of(context: Context, pageSize: String, textSizeSp: Float): PageSpec {
            val sp = textSizeSp + RENDERED_BUMP_SP
            return when (pageSize) {
                Seam.PAGE_LETTER -> paper(612, 792, sp)
                Seam.PAGE_A4 -> paper(595, 842, sp)
                else -> screen(context, sp)
            }
        }

        private fun paper(width: Int, height: Int, sp: Float) = PageSpec(
            width, height, PAPER_MARGIN_PT, PAPER_MARGIN_PT, PAPER_MARGIN_PT, PAPER_MARGIN_PT,
            em = sp * PAPER_POINTS_PER_SP, density = 1f, pixelsPerUnit = Seam.PAPER_DPI / 72f, pointsPerUnit = 1f,
        )

        /** The screen's own pixels, the editor's own margins and the editor's own type. */
        private fun screen(context: Context, sp: Float): PageSpec {
            val metrics = DisplayMetrics()
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION")
            if (display != null) display.getRealMetrics(metrics) else metrics.setTo(context.resources.displayMetrics)
            val w = minOf(metrics.widthPixels, metrics.heightPixels)
            val h = maxOf(metrics.widthPixels, metrics.heightPixels)
            fun dp(v: Float) = (v * metrics.density).roundToInt()
            val dpi = if (metrics.xdpi > 1f) metrics.xdpi else metrics.densityDpi.toFloat()
            return PageSpec(
                w, h, dp(20f), dp(24f), dp(20f), dp(32f),
                em = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics), density = metrics.density,
                pixelsPerUnit = 1f, pointsPerUnit = 72f / dpi,
            )
        }
    }
}

/**
 * **A document laid out on pages**: the rendered editor's own drawing ([RichCodec], the same
 * spans), flowed at the page's width and cut on line boundaries by `:markdown`'s paginator. A
 * line is never split; one taller than a page has a page to itself.
 */
class PageLayout(doc: RichDoc, val spec: PageSpec) {

    private val layout: StaticLayout
    val pages: List<MarkdownPaginator.Page>
    private val lineBottoms: IntArray

    init {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = spec.em
        }
        val spanned = RichCodec.toSpannable(doc, BlockMetrics(spec.em, spec.density))
        layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, spec.contentWidth)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .build()
        // The text ends in its last block's line break, and a layout gives that an empty line of
        // its own: it is not a line of the document.
        var count = layout.lineCount
        if (count > 0 && layout.getLineStart(count - 1) >= spanned.length) count--
        val lines = (0 until count).map { MarkdownPaginator.Line(layout.getLineTop(it), layout.getLineBottom(it)) }
        lineBottoms = IntArray(count) { layout.getLineBottom(it) }
        pages = if (lines.isEmpty()) emptyList() else MarkdownPaginator.paginate(lines, spec.contentHeight)
    }

    /** Draw page [index] onto [canvas], whose units are the layout's. */
    fun draw(canvas: Canvas, index: Int) {
        val page = pages[index]
        val bottom = lineBottoms[page.lastLine]
        canvas.save()
        canvas.translate(spec.marginLeft.toFloat(), (spec.marginTop - page.top).toFloat())
        canvas.clipRect(0, page.top, spec.contentWidth, bottom)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Page [index] as a lossless picture, at the page's pixels. */
    fun png(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(spec.widthPx, spec.heightPx, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(spec.pixelsPerUnit, spec.pixelsPerUnit)
            draw(canvas, index)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Every page as a PDF whose text is text: drawn onto the platform's own PDF canvas. */
    fun writePdf(out: OutputStream) {
        val pdf = PdfDocument()
        try {
            val w = (spec.width * spec.pointsPerUnit).roundToInt()
            val h = (spec.height * spec.pointsPerUnit).roundToInt()
            for (index in pages.indices) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, index + 1).create())
                page.canvas.scale(spec.pointsPerUnit, spec.pointsPerUnit)
                draw(page.canvas, index)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private companion object {
        /** The rendered editor's own. */
        const val LINE_SPACING = 1.15f
    }
}
