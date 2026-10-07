package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract

/**
 * The export screen's **render-only mode**: what it exports when there is no item — the
 * calendar's pages, drawn by the calendar's renderer under a key only it reads. Pure. No item
 * file, so no file exporter and no keying; no app of its own writing formats; the pages are the
 * whole of it, so the page formats (PDF, images) are the candidates and the paper toggle the one
 * option.
 */
object ExportRenderMode {

    /** The mode, from the three extras as they arrived; null for an ordinary export. */
    class Request(val kind: String, val key: String, val name: String)

    fun requestOf(kind: String?, key: String?, name: String?): Request? {
        if (kind.isNullOrEmpty() || key.isNullOrEmpty()) return null
        return Request(kind, key, name?.takeIf { it.isNotEmpty() } ?: key)
    }

    /** Whether an exporter of [sourceKind] is listed in this mode: only one that takes pages. */
    fun lists(sourceKind: Int): Boolean = sourceKind == ExportContract.SOURCE_PAGES

    /** The file stem: the request's name made safe, the key when nothing is left of it. */
    fun stem(request: Request): String = ExportNaming.base(request.name, request.key)
}
