package com.symmetricalpalmtree.soil.notesprout.export

import com.symmetricalpalmtree.soil.ext.PageBundle

/**
 * Sticky notes in an export: each note with content becomes an endnote page after the item's
 * pages, captioned with its number and its page, and the bundle's links carry the jumps both
 * ways (icon to endnote, caption to source). Pure; the baking is the service's.
 */
object Endnotes {

    const val CAPTION_PX = 60
    const val CAPTION_TEXT_PX = 32f
    const val CAPTION_INSET_PX = 16f

    class Source(
        val stickyId: String,
        /** The bundle position of the page the note sits on, 1-based. */
        val fromPage: Int,
        /** The page's number as the person knows it. */
        val fromPageLabel: Int,
        val iconL: Float, val iconT: Float, val iconR: Float, val iconB: Float,
        val contentW: Int, val contentH: Int,
        val pageW: Int, val pageH: Int,
    )

    class Note(val stickyId: String, val number: Int, val fromPage: Int, val fromPageLabel: Int, val page: Int, val contentW: Int, val contentH: Int) {
        val widthPx: Int get() = contentW
        val heightPx: Int get() = contentH + CAPTION_PX
    }

    class Plan(val notes: List<Note>, val links: List<PageBundle.Link>)

    fun caption(number: Int, fromPage: Int): String = "Note $number — from page $fromPage"

    fun plan(sources: List<Source>, pageCount: Int): Plan {
        require(pageCount >= 1) { "no pages" }
        val notes = ArrayList<Note>(sources.size)
        val links = ArrayList<PageBundle.Link>(sources.size * 2)
        sources.forEachIndexed { index, s ->
            require(s.fromPage in 1..pageCount) { "source page ${s.fromPage} outside 1..$pageCount" }
            val number = index + 1
            val page = pageCount + number
            val (w, h) = contentSize(s)
            notes += Note(s.stickyId, number, s.fromPage, s.fromPageLabel, page, w, h)
            if (s.iconR > s.iconL && s.iconB > s.iconT && s.iconL.isFinite() && s.iconT.isFinite() && s.iconR.isFinite() && s.iconB.isFinite()) {
                links += PageBundle.Link(s.fromPage, s.iconL, s.iconT, s.iconR, s.iconB, page)
            }
            links += PageBundle.Link(page, 0f, h.toFloat(), w.toFloat(), (h + CAPTION_PX).toFloat(), s.fromPage)
        }
        return Plan(notes, links)
    }

    /** A note's content size, falling back to the page's when the note never recorded one. */
    fun contentSize(s: Source): Pair<Int, Int> {
        val w = (if (s.contentW > 0) s.contentW else s.pageW).coerceIn(1, PageBundle.MAX_DIMENSION_PX)
        val h = (if (s.contentH > 0) s.contentH else s.pageH).coerceIn(1, PageBundle.MAX_DIMENSION_PX - CAPTION_PX)
        return w to h
    }
}
