package com.symmetricalpalmtree.soil.notesprout.links

import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload

/**
 * Every decision the link picker makes that is not a view: which shelf a prefill opens on, which
 * style latch is down, how the page cards are numbered, where a created page lands, and whether
 * what is chosen composes a payload at all. Pure, JVM-tested.
 *
 * Two rules worth naming, since neither can be seen in a screenshot:
 *
 * - **Numbering never drifts.** Page positions are counted over the notebook's whole page list
 *   and only then is the current page dropped, so the page after it is still "Page 4": the
 *   number a person counts to on paper.
 * - **A link never targets its own home.** The picker hides the current notebook, and
 *   [composeOk] refuses it anyway: hidden is a chrome fact, this is the contract.
 */
object LinkPickerModel {

    /** The picker's four shelves, in the order of the mode row. [CAL_DAY] is a day of the
     *  calendar, chosen in the shared day picker (Calsprout, 2026-10-06). */
    enum class PickMode { THIS_NOTEBOOK, NOTEBOOK, NOTEBOOK_PAGE, CAL_DAY }

    /** The shelf a prefill opens on; a fresh create, and any payload that cannot be read, opens
     *  on this notebook. An unreadable prefill is a silently fresh picker, never a dialog. */
    fun modeFor(decoded: LinkPayload.Decoded?): PickMode = when (decoded?.kind) {
        LinkPayload.KIND_ITEM -> PickMode.NOTEBOOK
        LinkPayload.KIND_ITEM_PAGE -> PickMode.NOTEBOOK_PAGE
        LinkPayload.KIND_CAL -> PickMode.CAL_DAY
        else -> PickMode.THIS_NOTEBOOK
    }

    /** Underline is the default. */
    fun chromeFor(decoded: LinkPayload.Decoded?): Int = decoded?.chrome ?: LinkPayload.CHROME_UNDERLINE

    /** Each page with its 1-based position in the whole notebook, [excludePageId] dropped after
     *  the count. Null excludes nothing: a foreign notebook has no current page. */
    fun <P> pageCards(pages: List<P>, idOf: (P) -> String, excludePageId: String?): List<Pair<P, Int>> =
        pages.mapIndexed { index, page -> page to index + 1 }.filter { (page, _) -> idOf(page) != excludePageId }

    /** The grid page holding item [itemIndex]; page 0 when out of range. */
    fun gridPageOf(itemIndex: Int, cardsPerPage: Int): Int =
        if (itemIndex < 0 || cardsPerPage <= 0) 0 else itemIndex / cardsPerPage

    fun pageCount(items: Int, cardsPerPage: Int): Int =
        if (items <= 0 || cardsPerPage <= 0) 1 else (items + cardsPerPage - 1) / cardsPerPage

    fun clampPage(index: Int, pageCount: Int): Int = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    /** Which create buttons a state shows. Never disabled: a control that cannot apply is not on screen. */
    data class CreateButtons(val newPage: Boolean, val newNotebook: Boolean)

    /** A page grid offers New page; a browse offers New notebook. [PickMode.NOTEBOOK_PAGE] is a
     *  browse until a notebook is [drilled] into, and then a page grid. */
    fun createButtons(mode: PickMode, drilled: Boolean): CreateButtons = when (mode) {
        PickMode.THIS_NOTEBOOK -> CreateButtons(newPage = true, newNotebook = false)
        PickMode.NOTEBOOK -> CreateButtons(newPage = false, newNotebook = true)
        PickMode.NOTEBOOK_PAGE -> if (drilled) CreateButtons(newPage = true, newNotebook = false) else CreateButtons(newPage = false, newNotebook = true)
        PickMode.CAL_DAY -> CreateButtons(newPage = false, newNotebook = false)
    }

    /**
     * The payload OK would return, or null when there is nothing to compose: no target yet, a
     * mode whose second half is missing, or the self-target the exclusions already hide. Null is
     * the cue to explain, never a disabled OK and never a silent no-op.
     */
    fun composeOk(mode: PickMode, chrome: Int, currentNotebookId: String, selectedNotebookId: String?, selectedPageId: String?, selectedDate: String? = null): String? {
        val (kind, itemId, pageId) = when (mode) {
            PickMode.THIS_NOTEBOOK -> Triple(LinkPayload.KIND_PAGE, null, selectedPageId ?: return null)
            PickMode.NOTEBOOK -> Triple(LinkPayload.KIND_ITEM, selectedNotebookId ?: return null, null)
            PickMode.NOTEBOOK_PAGE -> Triple(LinkPayload.KIND_ITEM_PAGE, selectedNotebookId ?: return null, selectedPageId ?: return null)
            PickMode.CAL_DAY -> Triple(LinkPayload.KIND_CAL, selectedDate ?: return null, null)
        }
        if (itemId == currentNotebookId) return null
        // The ids come from the index and from rows: untrusted enough that a malformed one must
        // not throw out of an OK tap. Here, a refusal reads as "incomplete".
        return runCatching { LinkPayload.encode(chrome, kind, itemId, pageId) }.getOrNull()
    }
}

/** Sizing for the page previews: a card keeps the real page's aspect, scaled to the cell width.
 *  A degenerate size falls back to [FALLBACK_ASPECT]; an extreme one is clamped. */
object PreviewMath {
    const val MIN_ASPECT = 0.5f
    const val MAX_ASPECT = 3f
    const val FALLBACK_ASPECT = 4f / 3f
    const val MAX_RENDER_EDGE_PX = 1024

    fun aspect(pageWidth: Int, pageHeight: Int): Float =
        if (pageWidth <= 0 || pageHeight <= 0) FALLBACK_ASPECT else (pageHeight.toFloat() / pageWidth).coerceIn(MIN_ASPECT, MAX_ASPECT)

    fun renderSize(cellWidthPx: Int, pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val w = cellWidthPx.coerceIn(1, MAX_RENDER_EDGE_PX)
        val h = (w * aspect(pageWidth, pageHeight)).toInt().coerceIn(1, MAX_RENDER_EDGE_PX)
        return w to h
    }
}
