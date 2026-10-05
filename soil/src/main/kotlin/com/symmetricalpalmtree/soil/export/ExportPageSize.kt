package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * The page size an item that flows is laid out at for an export. A document has no pages of
 * its own, so the export screen asks: a paper size, or this device's screen. Pure.
 */
object ExportPageSize {

    val CHOICES: List<String> = listOf(Seam.PAGE_LETTER, Seam.PAGE_A4, Seam.PAGE_SCREEN)
    const val DEFAULT = Seam.PAGE_LETTER

    /** A remembered choice is untrusted input: one this build does not offer reads as the default. */
    fun orDefault(stored: String?): String = if (stored in CHOICES) stored!! else DEFAULT

    /**
     * What a one-file pages exporter is told of the pictures' scale: a paper size was drawn at
     * [Seam.PAPER_DPI], so each pixel is `72 / PAPER_DPI` points; the screen's pixels are told
     * nothing, and stay a point each as a notebook's are.
     */
    fun specValues(pageSize: String): Map<String, String> =
        if (pageSize == Seam.PAGE_SCREEN) emptyMap() else mapOf(ExportContract.OPTION_PAGE_POINTS to (72f / Seam.PAPER_DPI).toString())
}
