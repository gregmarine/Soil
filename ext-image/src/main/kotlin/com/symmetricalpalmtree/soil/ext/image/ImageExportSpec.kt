package com.symmetricalpalmtree.soil.ext.image

import android.graphics.Bitmap
import com.symmetricalpalmtree.soil.ext.ExportContract

/** The spec, checked by construction, and what it means for the encoder. */
internal object ImageExportSpec {

    val SUPPORTED_OPTIONS: Set<String> = setOf(ExportContract.OPTION_PAGE_TEMPLATE, ExportContract.OPTION_IMAGE_FORMAT, ExportContract.OPTION_QUALITY)

    /** The encoder's settings: the format and its quality, 100 for the lossless one. */
    data class Encoding(val format: Bitmap.CompressFormat, val quality: Int)

    fun require(values: Map<String, String>, exportSecret: String?): Encoding {
        val unknown = values.keys.filter { it !in SUPPORTED_OPTIONS }.sorted()
        require(unknown.isEmpty()) { "options not offered by this exporter: ${unknown.joinToString(", ")}" }
        require(exportSecret == null) { "an export secret arrived that nothing asked for" }
        val format = when (val f = values[ExportContract.OPTION_IMAGE_FORMAT] ?: ExportContract.IMAGE_FORMAT_PNG) {
            ExportContract.IMAGE_FORMAT_PNG -> Bitmap.CompressFormat.PNG
            ExportContract.IMAGE_FORMAT_JPEG -> Bitmap.CompressFormat.JPEG
            ExportContract.IMAGE_FORMAT_WEBP -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> throw IllegalArgumentException("format '$f' is not offered by this exporter")
        }
        val quality = when (val q = values[ExportContract.OPTION_QUALITY] ?: ExportContract.QUALITY_BALANCED) {
            ExportContract.QUALITY_BEST -> 95
            ExportContract.QUALITY_BALANCED -> 85
            ExportContract.QUALITY_SMALL -> 70
            else -> throw IllegalArgumentException("quality '$q' is not offered by this exporter")
        }
        return Encoding(format, if (format == Bitmap.CompressFormat.PNG) 100 else quality)
    }
}
