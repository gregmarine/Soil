package com.symmetricalpalmtree.soil.sketchsprout.raster

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The codec: a field this build does not know is skipped, a default is written out so the row
 *  says what it means on its own. */
private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}

/** A page's grid, as the `guide_grid` row's `text` carries it. [kind] is `LINES` or `DOTS` —
 *  "off" is no row at all, never a stored kind — and [count] the cells across the page's width. */
@Serializable
data class GuideGrid(val kind: String, val count: Int, val visible: Boolean = true)

/** The reference image's settings, as the `guide_image` row's `text` carries them: [opacity] a
 *  percent. The pixels are the row's blob. */
@Serializable
data class GuideImage(val opacity: Int, val visible: Boolean = true)

/**
 * The border between a page's **guides** and the file (Notesprout SN's arc 51) — [RasterRows]'
 * sibling for the two rows that are tools rather than marks. Pure: text and bytes in and out, no
 * `Bitmap`, so every rule here is proved on a laptop.
 *
 * **Never a stored row that throws.** A row is read by [decodeGrid] / [decodeImage], which answer
 * null for text that does not parse or values outside what the screen would accept — so a foreign
 * or damaged row reads as "no guide" rather than as a crash, and the next save rewrites it.
 *
 * **Never routed through [RasterRows.typeFor] or the layer list.** Those drive the flatten, the
 * export, the cover and the raster saves; a guide in any of them would be a guide drawn into the
 * picture, which is the one thing it must never be.
 */
object GuideRows {

    const val KIND_LINES = "LINES"
    const val KIND_DOTS = "DOTS"

    /** The widest grid a stored count may name; the screen's ladder is far narrower. */
    const val MAX_COUNT = 64

    fun encodeGrid(grid: GuideGrid): String = codec.encodeToString(GuideGrid.serializer(), grid)

    fun encodeImage(image: GuideImage): String = codec.encodeToString(GuideImage.serializer(), image)

    /** A grid row's `text` read back, or null — no text, unparsable text, a kind that is neither
     *  lines nor dots, or a count outside 1..[MAX_COUNT]. */
    fun decodeGrid(text: String?): GuideGrid? {
        if (text == null) return null
        val grid = decode { codec.decodeFromString(GuideGrid.serializer(), text) } ?: return null
        val kindOk = grid.kind == KIND_LINES || grid.kind == KIND_DOTS
        return if (kindOk && grid.count in 1..MAX_COUNT) grid else null
    }

    /** An image row's `text` read back, or null — no text, unparsable text, or an opacity outside
     *  the percent. */
    fun decodeImage(text: String?): GuideImage? {
        if (text == null) return null
        val image = decode { codec.decodeFromString(GuideImage.serializer(), text) } ?: return null
        return if (image.opacity in 0..100) image else null
    }

    /** Is this image exactly this page's size? [RasterRows.fitsPage]'s rule: the reference image
     *  is a lossy WebP with alpha, `VP8X`-headed, which the header guard reads. */
    fun fitsPage(bytes: ByteArray, pageWidth: Int, pageHeight: Int): Boolean = RasterRows.fitsPage(bytes, pageWidth, pageHeight)

    private inline fun <T> decode(block: () -> T): T? = try { block() } catch (e: Exception) { null }
}
