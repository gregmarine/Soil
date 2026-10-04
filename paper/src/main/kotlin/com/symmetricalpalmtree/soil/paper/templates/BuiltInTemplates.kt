package com.symmetricalpalmtree.soil.paper.templates

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Paints a [TemplateKind] onto a bitmap. Thin on purpose: every position comes from
 * [TemplateGeometry]; this only holds a brush. The page-sized [render] is baked into a notebook's
 * `template` row, so a change here affects new paper only: a page never re-rules itself under old
 * ink.
 */
object BuiltInTemplates {

    /** The page-sized render: real 8 mm spacing at the panel's [dpi]. Null for blank. ARGB: it is stored. */
    fun render(kind: TemplateKind, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? = renderWith(
        kind, widthPx, heightPx,
        spacingPx = TemplateGeometry.spacingPx(dpi),
        lineWidthPx = TemplateGeometry.lineWidthPx(dpi),
        dotRadiusPx = TemplateGeometry.dotRadiusPx(dpi),
    )

    /**
     * A true miniature of the same paper: the pattern scaled honestly to a card, so 8 mm ruling
     * on a quarter-width card is 2 mm of card, which is what tells a dense grid from a loose one.
     * Feature sizes floor at 1 px, or a rule stops being drawn and the card reads as blank.
     */
    fun miniature(kind: TemplateKind, widthPx: Int, heightPx: Int, scale: Float, dpi: Float): Bitmap? {
        if (scale <= 0f) return null
        return renderWith(
            kind, widthPx, heightPx,
            spacingPx = TemplateGeometry.spacingPx(dpi) * scale,
            lineWidthPx = maxOf(1f, TemplateGeometry.lineWidthPx(dpi) * scale),
            dotRadiusPx = maxOf(1f, TemplateGeometry.dotRadiusPx(dpi) * scale),
        )
    }

    private fun renderWith(kind: TemplateKind, widthPx: Int, heightPx: Int, spacingPx: Float, lineWidthPx: Float, dotRadiusPx: Float): Bitmap? {
        if (kind == TemplateKind.BLANK || widthPx <= 0 || heightPx <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a ${widthPx}x$heightPx paper would not allocate")
            return null
        }
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        when (kind) {
            TemplateKind.LINED -> {
                paint.strokeWidth = lineWidthPx
                for (y in TemplateGeometry.linePositions(heightPx, spacingPx)) canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
            }
            TemplateKind.DOTTED -> {
                paint.style = Paint.Style.FILL
                for ((x, y) in TemplateGeometry.dotPositions(widthPx, heightPx, spacingPx)) canvas.drawCircle(x, y, dotRadiusPx, paint)
            }
            TemplateKind.GRID -> {
                paint.strokeWidth = lineWidthPx
                for (y in TemplateGeometry.gridPositionsY(heightPx, spacingPx)) canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                for (x in TemplateGeometry.gridPositionsX(widthPx, spacingPx)) canvas.drawLine(x, 0f, x, heightPx.toFloat(), paint)
            }
            TemplateKind.BLANK -> Unit
        }
        return bitmap
    }

    /**
     * Lossy WEBP at quality 100: measured on both Supernotes against lossless, which bloated line
     * art about tenfold and took a hundred seconds on an imported picture. Every read decodes by
     * the byte header, so old lossless blobs keep decoding beside new ones.
     */
    fun toWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 100, out)
        return out.toByteArray()
    }

    private const val TAG = "BuiltInTemplates"
}

/**
 * Paper that can be put on a page: the app's own arithmetic, or a picture and the fit it is laid
 * on with. [Blank] is neither: the absence of a row. [Image.bytes] are the stored original, never
 * a previous render, so one row lands right on pages of different sizes.
 */
sealed class PaperSource {
    object Blank : PaperSource()
    data class BuiltIn(val kind: TemplateKind) : PaperSource()
    class Image(val bytes: ByteArray, val fit: Int) : PaperSource()
}

/** The one place a [PaperSource] becomes page-sized pixels, and the one answer to "is this the same paper". */
object PagePaper {

    fun token(paper: PaperSource): String = when (paper) {
        PaperSource.Blank -> ""
        is PaperSource.BuiltIn -> TemplateToken.of(paper.kind)
        is PaperSource.Image -> TemplateToken.ofImage(paper.bytes, paper.fit)
    }

    /** The page-sized bitmap at the **page's** size, never the screen's; null when there is
     *  nothing to draw, which a caller takes as "write no row", never as blank. */
    fun render(paper: PaperSource, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? = when (paper) {
        PaperSource.Blank -> null
        is PaperSource.BuiltIn -> BuiltInTemplates.render(paper.kind, widthPx, heightPx, dpi)
        is PaperSource.Image -> renderImage(paper.bytes, paper.fit, widthPx, heightPx)
    }

    /** Decode bounded (untrusted bytes out of a database), then one blit onto a white page. */
    fun renderImage(bytes: ByteArray, fit: Int, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val src = Bitmaps.decodeBounded(bytes, maxOf(widthPx, heightPx) * 2) ?: return null
        return try {
            val page = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            page.eraseColor(Color.WHITE)
            drawFitted(page, src, fit)
            page
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a ${widthPx}x$heightPx page would not allocate")
            null
        } finally {
            src.recycle()
        }
    }

    /** Lay [src] onto [page] under [fit]: one blit, the same plan at every size. */
    fun drawFitted(page: Bitmap, src: Bitmap, fit: Int) {
        val plan = TemplateFit.plan(fit, src.width, src.height, page.width, page.height) ?: return
        Canvas(page).drawBitmap(
            src,
            Rect(plan.src.left.toInt(), plan.src.top.toInt(), plan.src.right.toInt(), plan.src.bottom.toInt()),
            RectF(plan.dst.left, plan.dst.top, plan.dst.right, plan.dst.bottom),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
    }

    private const val TAG = "PagePaper"
}

object Bitmaps {
    /** A sampled decode capped at [maxEdge] on the long side: bytes out of a database must not be
     *  able to allocate an unbounded bitmap on a memory-tight device. Null on garbage. */
    fun decodeBounded(bytes: ByteArray?, maxEdge: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty() || maxEdge <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
