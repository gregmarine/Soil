package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchPage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterTile

/**
 * One thing the hand did to a sketchbook that can be taken back (Notesprout SN's arc 43, with the
 * page edits owned here rather than by a host).
 *
 * g-paper keeps no history of its own, and on a raster page the record is **pixels**, not ids:
 * the graphite went into the page's graphite image at pen-up, the gel pen into its ink image, the
 * rubber took pixels off the graphite one — so the only record of what was there a moment ago is
 * the pixels that were there. [RasterChanged] carries them, and [bytes] is what lets the stack
 * bound a history that is no longer free to hold.
 *
 * **[PagesChanged] is the one non-pixel kind**: a page inserted or deleted from this screen. It
 * carries the page list before and after and the ids a delete soft-deleted, so the replay is one
 * [com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookStore.reconcile] in either direction.
 * It costs no bytes, so the byte budget can never evict one.
 *
 * **Every edit knows which page it happened on, and that is the whole design.** The history is
 * screen-level: someone who draws on page three, turns to page four and taps undo means "take
 * back the last thing I did" — so undoing it turns back to page three first. [pageIndex] rides
 * along beside [pageKey] because the replay has to know which way to turn and how far.
 */
sealed class SketchEdit {

    /** The page the edit happened on — the page a replay turns back to before it lands. */
    abstract val pageKey: String

    /** Where that page sat when the edit was made: the replay's direction and bound. */
    abstract val pageIndex: Int

    /** What keeping this entry costs, for the stack's byte budget. */
    abstract val bytes: Long

    /** The same entry, its page now at [index] — the history's re-index after a page insert or
     *  delete. An entry re-indexed to where it already is hands back **the very same object**. */
    abstract fun withIndex(index: Int): SketchEdit

    /**
     * One of the page's two rasters as it was, over the patch of page one contact changed — a
     * mark composited at pen-up, a whole rubbing sweep, a smudge, a bake. **One contact is one
     * entry**, and **one contact touches exactly one raster**, which is why [layer] is a field: a
     * patch carries no layer of its own, and a tile read from graphite can only be swapped back
     * into graphite. The entry **is its own inverse**: `swapPageRaster(layer, patches)` leaves the
     * tiles holding the other side, so one entry serves undo and redo with no second copy.
     * [tiles] are disjoint, so they can be swapped in any order.
     */
    class RasterChanged(
        override val pageKey: String,
        override val pageIndex: Int,
        val layer: RasterLayer,
        val tiles: List<RasterTile>,
    ) : SketchEdit() {
        override val bytes: Long get() = tiles.sumOf { it.bytes }

        /** The tiles are shared, not copied: they are the whole cost of an entry and nothing about
         *  them changes when a *different* page is inserted or removed. */
        override fun withIndex(index: Int): RasterChanged =
            if (index == pageIndex) this else RasterChanged(pageKey, index, layer, tiles)
    }

    /**
     * A page the hand made or took away from this screen: the live page list [before] and
     * [after], the content ids a delete took with the page ([takenIds], empty for an insert), and
     * the page each side lands on. Undo reconciles the file to [before] and restores the ids;
     * redo reconciles to [after] and soft-deletes them again. A row is restored in place, so a
     * page that comes back comes back with everything drawn on it.
     *
     * [bytes] is **zero**: the budget evicts the oldest entry that costs something, so a page
     * entry can never be thrown away to make room for pixels.
     */
    data class PagesChanged(
        val kind: Kind,
        override val pageKey: String,
        override val pageIndex: Int,
        val before: List<SketchPage>,
        val after: List<SketchPage>,
        val takenIds: List<String>,
        val landingBefore: String,
        val landingAfter: String,
    ) : SketchEdit() {
        enum class Kind { INSERTED, DELETED }

        override val bytes: Long get() = 0L

        override fun withIndex(index: Int): PagesChanged =
            if (index == pageIndex) this else copy(pageIndex = index)
    }

    companion object {
        /** How many bytes of before-image the undo side may hold — 48 MB, Paintsprout's number:
         *  five page-wide entries on the Nomad's page, and room for the hundreds of small ones a
         *  sitting of sketching produces. The stack evicts the **oldest costed entry** over it. */
        const val UNDO_BUDGET_BYTES: Long = 48L * 1024L * 1024L
    }
}
