package com.symmetricalpalmtree.soil.sketchsprout.export

import com.symmetricalpalmtree.soil.ext.PageBundle
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchPage

/** The pure half of a render: which pages, in what order, at what size. */
object RenderPlan {

    class Page(val ref: SketchPage, val number: Int, val widthPx: Int, val heightPx: Int)

    sealed class Outcome {
        class Ready(val pages: List<Page>) : Outcome()
        object Empty : Outcome()
        object Damaged : Outcome()
        object TooLong : Outcome()
    }

    /** [pageIds] narrows the item's pages; empty means every page. Order and numbers are the item's. */
    fun of(all: List<SketchPage>, pageIds: Collection<String>): Outcome {
        val wanted = pageIds.toHashSet()
        val pages = ArrayList<Page>()
        all.forEachIndexed { index, ref ->
            if (wanted.isNotEmpty() && ref.id !in wanted) return@forEachIndexed
            val w = ref.width.toInt(); val h = ref.height.toInt()
            if (w < 1 || h < 1) return Outcome.Damaged
            pages += Page(ref, index + 1, w, h)
        }
        if (pages.isEmpty()) return Outcome.Empty
        if (pages.size > PageBundle.MAX_PAGES) return Outcome.TooLong
        return Outcome.Ready(pages)
    }
}
