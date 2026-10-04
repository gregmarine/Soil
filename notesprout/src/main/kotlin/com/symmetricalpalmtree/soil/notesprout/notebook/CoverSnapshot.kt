package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * The library card's cover: the showing page as the engine renders it (paper and ink), scaled to
 * [LONG_EDGE_PX] on the long side, lossy WEBP q100. Only ever the cover, and only into the index
 * through the seam.
 */
object CoverSnapshot {

    const val LONG_EDGE_PX = 512

    fun encode(full: Bitmap): ByteArray {
        val scaled = scaleToLongEdge(full, LONG_EDGE_PX)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream()
        scaled.compress(format, 100, out)
        if (scaled !== full) scaled.recycle()
        return out.toByteArray()
    }

    private fun scaleToLongEdge(src: Bitmap, edge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= edge) return src
        val f = edge.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
    }
}
