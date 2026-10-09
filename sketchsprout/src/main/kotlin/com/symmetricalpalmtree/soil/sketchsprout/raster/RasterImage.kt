package com.symmetricalpalmtree.soil.sketchsprout.raster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * One of a page's two rasters turned into bytes, and back (Notesprout SN's, arcs 43–45) — the
 * half of the raster border that needs Android. The half that does not is [ImageHeader] and
 * [RasterRows].
 *
 * **Lossless WebP with alpha, because the drawing has to come back exactly.** A raster page has no
 * rows to re-render from: what is in the blob is the only copy of the work, so a codec that is
 * "very nearly" right is a codec that quietly rewrites the artist's marks every time the page is
 * saved. WebP's lossless mode meets it in appreciably fewer bytes than PNG on exactly this kind of
 * image — large, mostly transparent, with soft graphite grain over it. **RGBA, not grey+alpha**:
 * the ink is grey today and colour is coming on other platforms.
 *
 * **Alpha matters as much as losslessness.** Each raster is a *layer over the paper*: its unmarked
 * pixels are transparent so the paper shows through, the rubber lifts alpha rather than painting
 * white, and the two rasters are flattened one over the other.
 *
 * **Nothing here logs a pixel** — byte counts and sizes only.
 */
object RasterImage {

    private const val TAG = "RasterImage"

    /**
     * What `WEBP_LOSSLESS` is told to spend. **An effort, not a quality**: the image that comes back
     * is identical whatever is passed; the number buys search — 0 the fastest encode and the largest
     * file, 100 the slowest and the smallest. SN's Nomad measurement chose 100 on sparse pages;
     * on a dense page that is a minute's encode, so since 2026-10-08 the effort is [RasterEffort]'s
     * choice by the page's coverage, and this is the sparse page's.
     */
    const val WEBP_EFFORT: Int = RasterEffort.FULL

    /** Every [COVERAGE_STEP]th pixel on every [COVERAGE_STEP]th row is looked at for the coverage:
     *  forty thousand reads of a Nomad page, a few milliseconds, before an encode of seconds. */
    private const val COVERAGE_STEP = 8

    /** **Debug only** — the walk's door sets it to measure another effort on the same page; null
     *  is [WEBP_EFFORT]. Never read by a release build. */
    @Volatile
    var debugEffort: Int? = null

    /**
     * One raster as WebP bytes — **or an empty array for a blank layer**, which is the store's word
     * for "this page has no such raster" (the row is soft-deleted rather than a page of nothing
     * stored). A picture over [RasterRows.WATCH_BYTES] gets a line in the log and is saved anyway;
     * the refusal line is [RasterRows.MAX_BYTES] and the store draws it.
     *
     * **Call this off the main thread.** A page encode is hundreds of milliseconds to seconds.
     */
    fun encode(bitmap: Bitmap?): ByteArray {
        if (bitmap == null) return ByteArray(0)
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        compressLossless(bitmap, out, debugEffort ?: RasterEffort.choose(coverage(bitmap)))
        val bytes = out.toByteArray()
        if (bytes.size > RasterRows.WATCH_BYTES) {
            Log.w(TAG, "this page's raster is ${bytes.size} bytes, over the ${RasterRows.WATCH_BYTES}-byte watch line; saved anyway")
        }
        return bytes
    }

    /**
     * The bytes as one raster's page image, or null when they are not this page's.
     *
     * **The guard runs before the decoder, always.** [RasterRows.fitsPage] answers from the WebP
     * container's own header, and only once it has said yes does anything ask `BitmapFactory` for
     * memory. A null opens that layer blank, which is the honest state of a layer nothing could be
     * read onto; the pixels on disk are left exactly as they are.
     *
     * `ARGB_8888` because each raster is a layer over the paper: a config without an alpha channel
     * would turn every unmarked pixel into a white hole in whatever sits under it.
     */
    fun decode(bytes: ByteArray?, pageWidth: Int, pageHeight: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        if (!RasterRows.fitsPage(bytes, pageWidth, pageHeight)) {
            Log.w(TAG, "a stored raster is ${ImageHeader.size(bytes)} and the page is ${pageWidth}x$pageHeight — refused before decoding")
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            // Throwable, not Exception: the one that matters on this device — not enough memory for
            // the page — is an Error.
            Log.e(TAG, "a stored raster passed the header guard and would not decode", t)
            null
        }
        if (bitmap == null) Log.e(TAG, "a stored raster decoded to nothing")
        return bitmap
    }

    /** The one place the encoder is chosen. `WEBP_LOSSLESS` arrived in API 30; on 29 the plain
     *  `WEBP` constant at quality 100 is documented as lossless. */
    private fun compressLossless(bitmap: Bitmap, out: ByteArrayOutputStream, effort: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, effort, out)
        } else {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
    }

    /** The share of [bitmap]'s pixels that carry any mark (alpha above zero), sampled. */
    private fun coverage(bitmap: Bitmap): Double {
        var seen = 0
        var marked = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen++
                if (bitmap.getPixel(x, y) ushr 24 != 0) marked++
                x += COVERAGE_STEP
            }
            y += COVERAGE_STEP
        }
        return if (seen == 0) 0.0 else marked.toDouble() / seen
    }

    /** What the encoder's buffer starts at: a page's raster measures in the hundreds of kilobytes
     *  on real drawing, so the default handful of bytes means a dozen array copies per save. */
    private const val INITIAL_BUFFER_BYTES = 512 * 1024
}
