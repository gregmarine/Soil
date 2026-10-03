package com.symmetricalpalmtree.soil.ext.pdf

import com.symmetricalpalmtree.soil.ext.PageBundle

/** Bundle links, top-left pixels and 1-based pages, as PDF annotations: bottom-left origin and
 *  0-based pages. Pure; the pdfbox objects are made in [PdfAssembly]. */
internal object PdfLinks {

    data class Annotation(val pageIndex: Int, val llx: Float, val lly: Float, val urx: Float, val ury: Float, val targetIndex: Int)

    fun annotations(links: List<PageBundle.Link>, pageHeights: List<Int>): List<Annotation> =
        links.map { link ->
            val pageIndex = link.fromPage - 1
            val targetIndex = link.toPage - 1
            require(pageIndex in pageHeights.indices && targetIndex in pageHeights.indices) { "link ${link.fromPage}→${link.toPage} outside the ${pageHeights.size} pages" }
            val pageH = pageHeights[pageIndex].toFloat()
            Annotation(pageIndex, link.l, pageH - link.b, link.r, pageH - link.t, targetIndex)
        }
}
