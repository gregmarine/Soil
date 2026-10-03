package com.symmetricalpalmtree.soil.ext.image

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.ExporterInfo
import com.symmetricalpalmtree.soil.ext.OptionDescriptor
import com.symmetricalpalmtree.soil.ext.PageBundle

/**
 * What the image exporter offers: one picture per page, in PNG, JPEG or WebP (Soil names the
 * file after the choice), a quality for the two lossy formats, and the paper under the ink.
 * The declared extension and MIME are the PNG default; Soil swaps them for the choice.
 */
internal object ImageDescriptor {

    val options: List<OptionDescriptor> = listOf(
        OptionDescriptor.choice(
            ExportContract.OPTION_IMAGE_FORMAT, "Format",
            listOf(ExportContract.IMAGE_FORMAT_PNG, ExportContract.IMAGE_FORMAT_JPEG, ExportContract.IMAGE_FORMAT_WEBP),
            listOf("PNG (lossless)", "JPEG", "WebP"),
            ExportContract.IMAGE_FORMAT_PNG,
        ),
        OptionDescriptor.choice(
            ExportContract.OPTION_QUALITY, "Quality (JPEG and WebP)",
            listOf(ExportContract.QUALITY_BEST, ExportContract.QUALITY_BALANCED, ExportContract.QUALITY_SMALL),
            listOf("Best", "Balanced", "Small"),
            ExportContract.QUALITY_BALANCED,
        ),
        OptionDescriptor.toggle(ExportContract.OPTION_PAGE_TEMPLATE, "Include page template", true),
    )

    fun info(): ExporterInfo = ExporterInfo("Image per page", "png", "image/png", options, ExportContract.SOURCE_PAGES, PageBundle.VERSION_1, ExportContract.DELIVERY_PER_PAGE)
}
