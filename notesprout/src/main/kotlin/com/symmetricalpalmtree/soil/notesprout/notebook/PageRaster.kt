package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.paper.templates.BuiltInTemplates
import java.io.IOException

/**
 * One page at full fidelity at its own pixel size: white, its paper scaled into the page rect,
 * then its content in the page's own layering ([PageDraw]), encoded as WEBP. What a page becomes
 * when it is saved as a template, and what an export will bake from, so the two can never drift.
 * Throws [IOException] when the page would not allocate: a truncated picture must never reach
 * the library.
 */
object PageRaster {

    fun toWebp(widthPx: Int, heightPx: Int, template: Bitmap?, content: PageContent, density: Float, paints: PagePaints): ByteArray {
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${widthPx}x$heightPx page would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            if (template != null) canvas.drawBitmap(template, null, Rect(0, 0, widthPx, heightPx), templatePaint)
            PageDraw.drawContent(canvas, content, density, paints)
            BuiltInTemplates.toWebp(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The same page, lossless: what an export bakes, so a lossless output stays exact. */
    fun toPng(widthPx: Int, heightPx: Int, template: Bitmap?, content: PageContent, density: Float, paints: PagePaints): ByteArray {
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${widthPx}x$heightPx page would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            if (template != null) canvas.drawBitmap(template, null, Rect(0, 0, widthPx, heightPx), templatePaint)
            PageDraw.drawContent(canvas, content, density, paints)
            encodePng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun encodePng(bitmap: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream(bitmap.width * bitmap.height / 8)
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("the page would not encode")
        return out.toByteArray()
    }

    private val templatePaint = Paint(Paint.FILTER_BITMAP_FLAG)
}
