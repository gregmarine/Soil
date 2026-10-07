package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideGrid
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideRows

/**
 * The screen's model of **one page's guides** (Notesprout SN's arc 51) — the grid's kind, count
 * and visibility, and the reference image's opacity, visibility and presence. Pure Kotlin: the
 * [GuidesBar] paints from it, [GuideSheet] draws from it, and the two rows are written from it.
 *
 * **Out of range is the default, never an exception** ([of]): a count or an opacity this build does
 * not offer reads as the default, each field on its own.
 *
 * **Hidden is not gone**: a hidden grid keeps its kind and count, a hidden image its pixels and
 * opacity. **Off is gone** for the grid (its row soft-deleted) and **Remove** is gone for the
 * image — but the screen keeps the grid's count for the rest of the sitting, so Off then Lines
 * comes back at the count it left.
 */
data class GuideState(
    val gridKind: Kind,
    /** Cells across the page's width — always one of [GuideSheet.COUNTS]. */
    val gridCount: Int,
    val gridVisible: Boolean,
    /** Percent — always one of [GuideSheet.OPACITIES]. */
    val imageOpacity: Int,
    val imageVisible: Boolean,
    /** Whether the page carries a reference image row at all. */
    val hasImage: Boolean,
) {

    enum class Kind { OFF, LINES, DOTS }

    val gridOn: Boolean get() = gridKind != Kind.OFF
    val showsGrid: Boolean get() = gridOn && gridVisible
    val showsImage: Boolean get() = hasImage && imageVisible
    val showsAnything: Boolean get() = showsGrid || showsImage

    /** A kind from the Off · Lines · Dots latches. Picking Lines or Dots **shows** the grid. The
     *  count is kept through Off. */
    fun withGrid(kind: Kind): GuideState = copy(gridKind = kind, gridVisible = if (kind == Kind.OFF) gridVisible else true)

    /** A count from the ladder; off-ladder is the default. A count picked with the grid off turns
     *  it on as lines. */
    fun withCount(count: Int): GuideState = copy(
        gridKind = if (gridOn) gridKind else DEFAULT_KIND,
        gridCount = ladderCount(count),
        gridVisible = true,
    )

    /** An opacity from the ladder; off-ladder is the default. Shows the image. */
    fun withOpacity(percent: Int): GuideState = copy(imageOpacity = ladderOpacity(percent), imageVisible = true)

    fun toggleGridVisible(): GuideState = copy(gridVisible = !gridVisible)
    fun toggleImageVisible(): GuideState = copy(imageVisible = !imageVisible)

    /** A freshly picked image: present and shown, at the opacity already chosen. */
    fun withImage(): GuideState = copy(hasImage = true, imageVisible = true)

    /** Remove: no image. The opacity is kept for the next pick in this sitting. */
    fun withoutImage(): GuideState = copy(hasImage = false, imageVisible = true)

    /** The grid row this state asks for, or null for Off (no row). */
    fun toGridRow(): GuideGrid? = when (gridKind) {
        Kind.OFF -> null
        Kind.LINES -> GuideGrid(GuideRows.KIND_LINES, gridCount, gridVisible)
        Kind.DOTS -> GuideGrid(GuideRows.KIND_DOTS, gridCount, gridVisible)
    }

    /** The image row's settings this state asks for — meaningful only to a page with an image row. */
    fun toImageRow(): GuideImage = GuideImage(imageOpacity, imageVisible)

    companion object {
        val DEFAULT_KIND: Kind = Kind.LINES

        /** A page with neither row: no grid (count ready at the default), no image. */
        val NONE: GuideState = GuideState(Kind.OFF, GuideSheet.DEFAULT_COUNT, true, GuideSheet.DEFAULT_OPACITY, true, hasImage = false)

        /** What the rows said, read as a state — each field falling back on its own default. */
        fun of(grid: GuideGrid?, image: GuideImage?, hasImage: Boolean): GuideState = GuideState(
            gridKind = when (grid?.kind) { GuideRows.KIND_LINES -> Kind.LINES; GuideRows.KIND_DOTS -> Kind.DOTS; else -> Kind.OFF },
            gridCount = ladderCount(grid?.count ?: GuideSheet.DEFAULT_COUNT),
            gridVisible = grid?.visible ?: true,
            imageOpacity = ladderOpacity(image?.opacity ?: GuideSheet.DEFAULT_OPACITY),
            imageVisible = image?.visible ?: true,
            hasImage = hasImage,
        )

        private fun ladderCount(count: Int): Int = if (count in GuideSheet.COUNTS) count else GuideSheet.DEFAULT_COUNT
        private fun ladderOpacity(percent: Int): Int = if (percent in GuideSheet.OPACITIES) percent else GuideSheet.DEFAULT_OPACITY
    }
}
