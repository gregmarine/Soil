package com.symmetricalpalmtree.soil.sketchsprout.raster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * The page as the export and the cover see it: **white, the paper, the graphite, the ink over
 * it** — the engine's own flatten order, in true greys, never the panel's dither. Guides are not
 * here: they are what the page lies on, not the page.
 *
 * One page-sized bitmap in, one out; the paper is drawn 1:1 from the origin as the engine draws
 * a sheet, the rasters likewise. Nothing is recycled here: the caller owns every input.
 */
object PageFlatten {

    fun flatten(width: Int, height: Int, paper: Bitmap?, graphite: Bitmap?, ink: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paper?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        graphite?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        ink?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        return out
    }
}
