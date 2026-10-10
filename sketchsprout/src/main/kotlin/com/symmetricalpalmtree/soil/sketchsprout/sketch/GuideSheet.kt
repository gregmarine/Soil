package com.symmetricalpalmtree.soil.sketchsprout.sketch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The **sheet** — the one page-sized picture the screen hands g-paper's `setSheet`: the page's
 * paper, the reference image at its opacity over it, and the grid over both. g-paper draws it
 * over white and under the rasters, on the window and on the Supernote panel, and never
 * exports, covers, rubs or smudges it. The paper is the export's and the cover's business
 * ([com.symmetricalpalmtree.soil.sketchsprout.raster.PageFlatten]); the guides are nobody's but
 * the glass's.
 *
 * **The walk knobs live here and nowhere else**: the grid's tone, its line weight and dot size,
 * the two ladders the panel offers, their defaults, and the reference image's encode quality.
 * The grid tone is page content, not chrome: the grey the sheet is made of, as a pencil's shade is.
 */
object GuideSheet {

    /** Atelier's level 9 (`#aaaaaa`) — light enough to draw over, dark enough to dither to a
     *  visible dotted line on the panel. */
    const val GRID_TONE: Int = 0xFFAAAAAA.toInt()

    const val LINE_PX: Int = 2
    const val DOT_RADIUS_PX: Float = 4f

    /** Cells across the page's width, as the panel offers them (SN's walks: "up to 32"). */
    val COUNTS: List<Int> = listOf(2, 4, 6, 8, 12, 16, 20, 24, 28, 32)
    const val COUNT_ROW_BREAK: Int = 5
    fun countRows(): List<List<Int>> = COUNTS.chunked(COUNT_ROW_BREAK)

    /** The reference image's opacities, percent. */
    val OPACITIES: List<Int> = listOf(10, 25, 50, 75)

    const val DEFAULT_COUNT: Int = 4
    const val DEFAULT_OPACITY: Int = 25

    /** The reference image's lossy WebP quality: a thing to trace, seen at a quarter strength
     *  through a dither, never a thing to keep. */
    const val IMAGE_QUALITY: Int = 90

    /**
     * The sheet for [state] over [paper] on a [pageWidth] × [pageHeight] page — `ARGB_8888` — or
     * **null** when there is nothing at all to lay (no paper, no guide shown), which is
     * `setSheet(null)`. [paper] is drawn first, 1:1 from the origin; [image] is the stored
     * reference, already page-sized with the fit baked in, at the state's opacity; the grid goes
     * over both. Off the main thread: a page-sized allocation and up to two page-sized blits.
     */
    fun render(pageWidth: Int, pageHeight: Int, paper: Bitmap?, state: GuideState, image: Bitmap?): Bitmap? {
        val drawPaper = paper != null && !paper.isRecycled
        val drawImage = state.showsImage && image != null && !image.isRecycled
        val grid = if (state.showsGrid) GridLayout.plan(state.gridKind, state.gridCount, pageWidth, pageHeight) else null
        if (!drawPaper && !drawImage && grid == null) return null
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val sheet = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        if (drawPaper) canvas.drawBitmap(paper!!, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (drawImage) canvas.drawBitmap(image!!, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = state.imageOpacity * 255 / 100 })
        if (grid != null) drawGrid(canvas, grid, pageWidth, pageHeight)
        return sheet
    }

    /** Lines as whole-pixel bars (crisp on the dither); dots as antialiased discs at every crossing. */
    private fun drawGrid(canvas: Canvas, plan: GridLayout.Plan, pageWidth: Int, pageHeight: Int) {
        if (plan.kind == GuideState.Kind.DOTS) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_TONE; style = Paint.Style.FILL }
            for (y in plan.ys) for (x in plan.xs) canvas.drawCircle(x, y, DOT_RADIUS_PX, paint)
            return
        }
        val paint = Paint().apply { color = GRID_TONE; style = Paint.Style.FILL }
        val w = pageWidth.toFloat(); val h = pageHeight.toFloat()
        for (x in plan.xs) { val left = barStart(x).toFloat(); canvas.drawRect(left, 0f, left + LINE_PX, h, paint) }
        for (y in plan.ys) { val top = barStart(y).toFloat(); canvas.drawRect(0f, top, w, top + LINE_PX, paint) }
    }

    private fun barStart(at: Float): Int = (at - LINE_PX / 2f).roundToInt()

    /**
     * A picked picture as the page's stored reference: a fresh page-sized transparent bitmap with
     * the picture **fit** into it — whole, centred, aspect kept. **One pixel is never fully
     * opaque**, on purpose: libwebp writes a simple `VP8 ` file, with no `VP8X` header, for a
     * picture with no transparency at all, and the load guard reads the page size from `VP8X`;
     * so the corner pixel's alpha is taken to 254 — invisible at any opacity offered.
     */
    fun fitToPage(source: Bitmap, pageWidth: Int, pageHeight: Int): Bitmap? {
        if (source.width <= 0 || source.height <= 0 || pageWidth <= 0 || pageHeight <= 0) return null
        val scale = minOf(pageWidth.toFloat() / source.width, pageHeight.toFloat() / source.height)
        val w = source.width * scale; val h = source.height * scale
        val left = (pageWidth - w) / 2f; val top = (pageHeight - h) / 2f
        val page = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        Canvas(page).drawBitmap(source, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        val corner = page.getPixel(0, 0)
        if (corner ushr 24 == 0xFF) page.setPixel(0, 0, (corner and 0x00FFFFFF) or (0xFE shl 24))
        return page
    }

    /** [page] as the stored reference's bytes: lossy WebP with alpha at [IMAGE_QUALITY]. */
    fun encode(page: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) page.compress(Bitmap.CompressFormat.WEBP_LOSSY, IMAGE_QUALITY, out)
        else @Suppress("DEPRECATION") page.compress(Bitmap.CompressFormat.WEBP, IMAGE_QUALITY, out)
        return out.toByteArray()
    }

    /** The smallest `inSampleSize` power of two that keeps the decoded picture's long edge at or
     *  above the page's — the fit only ever scales down, and a 48 MP photo never arrives whole. */
    fun sampleSize(srcWidth: Int, srcHeight: Int, pageWidth: Int, pageHeight: Int): Int {
        val srcLong = maxOf(srcWidth, srcHeight); val pageLong = maxOf(pageWidth, pageHeight)
        if (srcLong <= 0 || pageLong <= 0) return 1
        var sample = 1
        while (srcLong / (sample * 2) >= pageLong) sample *= 2
        return sample
    }
}
