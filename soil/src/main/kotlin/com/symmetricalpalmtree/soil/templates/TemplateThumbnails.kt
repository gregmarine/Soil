package com.symmetricalpalmtree.soil.templates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import com.symmetricalpalmtree.soil.paper.templates.Bitmaps
import com.symmetricalpalmtree.soil.paper.templates.BuiltInTemplates
import com.symmetricalpalmtree.soil.paper.templates.PagePaper

/**
 * The card art: a **true miniature**, the page scaled honestly at its own aspect, so density tells
 * two papers apart. Rendered off Main, cached by `id:stamp:width` in bytes, never recycled (the
 * cache is the only owner). A picture is laid on under the row's own fit: a card that fitted what
 * the page stretches would be the one thing a miniature must never do.
 */
object TemplateThumbnails {

    fun bitmap(card: TemplateCard, cellWidthPx: Int, pageWidthPx: Int, pageHeightPx: Int, dpi: Float, image: ByteArray? = null): Bitmap? {
        if (cellWidthPx < 1 || pageWidthPx < 1 || pageHeightPx < 1) return null
        if (card is TemplateCard.Folder || card is TemplateCard.Defaults) return null
        val key = key(card, cellWidthPx)
        cache.get(key)?.let { return it }
        val bmp = render(card, cellWidthPx, pageWidthPx, pageHeightPx, dpi, image) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** True when the card's art would come from the cache, so its bytes need not be read at all. */
    fun isCached(card: TemplateCard, cellWidthPx: Int): Boolean = cache.get(key(card, cellWidthPx)) != null

    private fun key(card: TemplateCard, cellWidthPx: Int) = "${card.id}:${card.stamp}:$cellWidthPx"

    private fun render(card: TemplateCard, cellWidthPx: Int, pageWidthPx: Int, pageHeightPx: Int, dpi: Float, image: ByteArray?): Bitmap? {
        val aspect = (pageHeightPx.toFloat() / pageWidthPx).coerceIn(0.5f, 3f)
        val w = cellWidthPx.coerceAtMost(1024)
        val h = (w * aspect).toInt().coerceIn(1, 1024)
        val scale = w / pageWidthPx.toFloat()
        val bmp = when (card) {
            is TemplateCard.BuiltIn -> BuiltInTemplates.miniature(card.kind, w, h, scale, dpi)
            else -> blankPage(w, h)
        } ?: return null
        if (card is TemplateCard.Static && image != null) {
            val src = Bitmaps.decodeBounded(image, DECODE_EDGE)
            if (src != null) try { PagePaper.drawFitted(bmp, src, card.fit) } finally { src.recycle() }
        }
        Canvas(bmp).drawRect(0.5f, 0.5f, bmp.width - 0.5f, bmp.height - 0.5f, border)
        return bmp
    }

    private fun blankPage(w: Int, h: Int): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "a ${w}x$h thumbnail would not allocate")
        null
    }

    private val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }

    /** Bounded in bytes: a whole grid page at the widest card the app draws, with room over. */
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private const val DECODE_EDGE = 1024
    private const val TAG = "TemplateThumbnails"
}
