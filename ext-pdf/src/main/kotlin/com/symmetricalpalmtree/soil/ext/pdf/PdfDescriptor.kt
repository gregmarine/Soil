package com.symmetricalpalmtree.soil.ext.pdf

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.ExporterInfo
import com.symmetricalpalmtree.soil.ext.OptionDescriptor
import com.symmetricalpalmtree.soil.ext.PageBundle

/** What the PDF exporter offers: the paper under the ink (Soil's work) and a password (ours). */
internal object PdfDescriptor {

    val options: List<OptionDescriptor> = listOf(
        OptionDescriptor.toggle(ExportContract.OPTION_PAGE_TEMPLATE, "Include page template", true),
        OptionDescriptor.toggle(ExportContract.OPTION_PROTECT, "Password-protect", false),
    )

    fun info(): ExporterInfo = ExporterInfo("PDF document", "pdf", "application/pdf", options, ExportContract.SOURCE_PAGES, PageBundle.VERSION)
}
