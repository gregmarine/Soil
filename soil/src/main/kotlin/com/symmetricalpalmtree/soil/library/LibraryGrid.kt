package com.symmetricalpalmtree.soil.library

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.IndexSchema
import com.symmetricalpalmtree.soil.paper.core.GridMath
import com.symmetricalpalmtree.soil.paper.templates.Bitmaps
import java.util.Date

/**
 * One page of library cards in [container], the template grid's shape: measured once against the
 * real band, paged, never scrolling. The host owns the listing, the page and the covers, decoded
 * off Main ([decodeCover]); a missing cover is blank paper. [bind] removes only the grid it added last: the empty state is a
 * sibling in the container.
 */
class LibraryGrid(
    private val container: ViewGroup,
    private val onTap: (LibraryCard) -> Unit,
    private val onLongPress: ((LibraryCard) -> Unit)?,
) {
    private var columns = 1
    private var gap = 0
    private var cardWidth = 0
    private var cardHeight = 0
    var cardsPerPage = 1
        private set
    private var currentGrid: View? = null

    fun measure(context: Context, containerWidth: Int, containerHeight: Int) {
        val res = context.resources
        val minCardWidth = res.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.library_card_min_width)
        gap = res.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.library_card_gap)
        columns = GridMath.columns(containerWidth, minCardWidth)
        cardsPerPage = GridMath.cardsPerPage(containerWidth, containerHeight, minCardWidth)
        cardWidth = (GridMath.cardWidthPx(containerWidth, minCardWidth) - gap).coerceAtLeast(1)
        cardHeight = (cardWidth * GridMath.CARD_ASPECT).toInt().coerceAtLeast(1)
    }

    fun bind(items: List<LibraryCard>, pageIndex: Int, covers: Map<String, Bitmap?>, selectedId: String? = null) {
        currentGrid?.let { container.removeView(it) }
        currentGrid = null
        val range = GridMath.pageRange(pageIndex, cardsPerPage, items.size)
        if (range.isEmpty()) return
        val context = container.context
        val inflater = LayoutInflater.from(context)
        val grid = GridLayout(context).apply {
            columnCount = columns
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val half = gap / 2
        for (i in range) {
            val card = items[i]
            val view = when (card) {
                is LibraryCard.FolderCard -> folderCard(inflater, card)
                is LibraryCard.ItemCard -> itemCard(inflater, context, card, covers[card.id])
                is LibraryCard.PageCard -> pageCard(inflater, context, card, covers[card.item.id])
            }
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = cardHeight
                setMargins(half, half, half, half)
            }
            view.isSelected = selectedId != null && card.id == selectedId
            view.setOnClickListener { onTap(card) }
            // A page card names a page; the sheet acts on an item. No long press on it.
            if (card !is LibraryCard.PageCard) onLongPress?.let { handler -> view.setOnLongClickListener { handler(card); true } }
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }

    private fun folderCard(inflater: LayoutInflater, card: LibraryCard.FolderCard): View =
        inflater.inflate(R.layout.card_folder, container, false).apply {
            findViewById<TextView>(R.id.folderName).text = card.name
        }

    private fun itemCard(inflater: LayoutInflater, context: Context, card: LibraryCard.ItemCard, bmp: Bitmap?): View {
        val view = inflater.inflate(R.layout.card_notebook, container, false)
        val item = card.item
        view.findViewById<TextView>(R.id.cardName).text = item.name
        val d = Date(item.updatedAt)
        view.findViewById<TextView>(R.id.cardDate).text = card.subtitle
            ?: "${DateFormat.getMediumDateFormat(context).format(d)} ${DateFormat.getTimeFormat(context).format(d)}"
        view.findViewById<View>(R.id.pinBadge).visibility = if (card.pinned) View.VISIBLE else View.GONE
        view.findViewById<ImageView>(R.id.kindGlyph).setImageResource(kindGlyph(item.kind))
        val cover = view.findViewById<ImageView>(R.id.coverImage)
        if (bmp != null) {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(bmp)
        } else {
            cover.setImageDrawable(null)
        }
        return view
    }

    /** A tagged page: the item's cover and kind, `Name · Page N` on the name line, the place and the tag under it. */
    private fun pageCard(inflater: LayoutInflater, context: Context, card: LibraryCard.PageCard, bmp: Bitmap?): View {
        val view = inflater.inflate(R.layout.card_notebook, container, false)
        val item = card.item
        view.findViewById<TextView>(R.id.cardName).text =
            if (card.pageNumber != null) context.getString(R.string.search_page_card, item.name, card.pageNumber)
            else context.getString(R.string.search_page_card_unnumbered, item.name)
        view.findViewById<TextView>(R.id.cardDate).text = card.subtitle
        view.findViewById<View>(R.id.pinBadge).visibility = View.GONE
        view.findViewById<ImageView>(R.id.kindGlyph).setImageResource(kindGlyph(item.kind))
        val cover = view.findViewById<ImageView>(R.id.coverImage)
        if (bmp != null) {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(bmp)
        } else {
            cover.setImageDrawable(null)
        }
        return view
    }

    /** How a kind is told at a glance: one Tabler glyph in the card's corner. */
    private fun kindGlyph(kind: String): Int = when (kind) {
        // The Note app's own mark, the cover with three tabs (Greg, 2026-10-10).
        IndexSchema.KIND_NOTEBOOK -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_notebook_mark
        // The Sketch app's own mark, the pencil (Greg, 2026-10-10); the sketching scribble is the Scratch Pad's.
        IndexSchema.KIND_SKETCHBOOK -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_pencil
        else -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_text
    }

    companion object {
        /** Covers are written small; the decode is bounded whatever the blob claims. */
        private const val COVER_DECODE_EDGE = 512

        /** A cover's bytes as the card shows them. IO: never on Main. */
        fun decodeCover(bytes: ByteArray?): Bitmap? = Bitmaps.decodeBounded(bytes, COVER_DECODE_EDGE)
    }
}
