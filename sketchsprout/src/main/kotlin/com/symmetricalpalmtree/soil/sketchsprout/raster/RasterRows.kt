package com.symmetricalpalmtree.soil.sketchsprout.raster

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema

/**
 * The border between the picture on a page and a row in the sketchbook, crossed in both
 * directions — pure: bytes in, bytes out, **no `Bitmap` anywhere**, so the part of a sketch page
 * that can be *proved* is proved on a laptop. The encoding and decoding themselves need Android
 * and are [RasterImage]'s; the rules about them live here.
 *
 * **Two rows, named by layer.** A sketch is a graphite raster and an ink raster, and which is
 * which is the *row's type*: [typeFor] is the one place the two names are chosen, so nothing
 * downstream can invent a third. The layer itself is g-paper's [RasterLayer], because the engine
 * routes a mark by its style and this border only ever follows the layer the engine names.
 *
 * **The size guard reads the header, never the image.** [fitsPage] answers from the first 30 bytes
 * through [ImageHeader], so a row whose picture is the wrong shape for its page is refused before
 * anything has allocated anything: a page image at the Nomad's size is about 9.5 MB decoded, and a
 * corrupt or foreign header claiming a larger one is a way to take the process down while the
 * person is drawing.
 *
 * **The cap is the seam's.** A raster crosses the seam whole, as one value, and the seam carries
 * a value of at most [SeamLimits.MAX_VALUE_BYTES] — the same 6 MiB Notesprout SN's host drew the
 * line at, by the same reason (the cursor window). The store refuses above it; [WATCH_BYTES] is
 * where a log line starts saying a page is getting heavy.
 */
object RasterRows {

    /** The most a raster may weigh as bytes in the file: the seam's cap on one value. */
    const val MAX_BYTES: Int = SeamLimits.MAX_VALUE_BYTES

    /** Where a save starts warning. Two thirds of the cap, so a page reads as heavy well before it
     *  is refused. */
    const val WATCH_BYTES: Int = 4 * 1024 * 1024

    /** The order every loop over the rasters takes: graphite first, then ink — the flatten order,
     *  the load order, the save order, so a log line naming two layers always names them the same
     *  way round. */
    val LAYERS: List<RasterLayer> = listOf(RasterLayer.GRAPHITE, RasterLayer.INK)

    /** The row type the raster [layer] names. Exhaustive by construction. */
    fun typeFor(layer: RasterLayer): String = when (layer) {
        RasterLayer.GRAPHITE -> SketchbookSchema.TYPE_SKETCH_GRAPHITE
        RasterLayer.INK -> SketchbookSchema.TYPE_SKETCH_INK
    }

    /** The raster a row [type] names, or null for any other row. */
    fun layerOf(type: String): RasterLayer? = when (type) {
        SketchbookSchema.TYPE_SKETCH_GRAPHITE -> RasterLayer.GRAPHITE
        SketchbookSchema.TYPE_SKETCH_INK -> RasterLayer.INK
        else -> null
    }

    /**
     * Is this picture the picture of *this* page? Exactly, in both directions — not "no larger
     * than": a page image registers with the page one-to-one, so an image of any other size is
     * some other page, or a blob that is not a page at all. A page with no recorded size fails
     * too: there is nothing to check against, and an unbounded decode is what this prevents.
     */
    fun fitsPage(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Boolean =
        pageWidth > 0 && pageHeight > 0 && ImageHeader.matches(bytes, pageWidth, pageHeight)

    /** A raster's name for a log line — a word, never a pixel. */
    fun name(layer: RasterLayer): String = if (layer == RasterLayer.INK) "ink" else "graphite"
}
