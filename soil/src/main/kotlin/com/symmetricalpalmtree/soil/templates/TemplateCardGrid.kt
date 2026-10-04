package com.symmetricalpalmtree.soil.templates

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.paper.core.GridMath

/**
 * The card grid: measured against the real band once, paginated, never scrolling, the library's
 * card footprint. Dumb about content: the host hands each card its art and decides what a tap
 * and a long press mean. [bind] removes only the grid it added last, since the empty state is a
 * sibling in the same container.
 */
class TemplateCardGrid(
    private val container: ViewGroup,
    private val onTap: (TemplateCard) -> Unit,
    private val onLongPress: (TemplateCard) -> Unit,
) {
    private var columns = 1
    private var gap = 0
    private var cardHeight = 0
    var cardWidth = 0
        private set
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

    /** [art] by card id (a missing entry is an empty band), [ticked] the paper in force, [pinned] the badges. */
    fun bind(items: List<TemplateCard>, pageIndex: Int, art: Map<String, Bitmap?>, ticked: Set<String>, pinned: Set<String>) {
        currentGrid?.let { container.removeView(it) }
        currentGrid = null
        val range = GridMath.pageRange(pageIndex, cardsPerPage, items.size)
        if (range.isEmpty()) return
        val inflater = LayoutInflater.from(container.context)
        val grid = GridLayout(container.context).apply {
            columnCount = columns
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val half = gap / 2
        for (i in range) {
            val item = items[i]
            val isFolder = item is TemplateCard.Folder || item is TemplateCard.Defaults
            val view = if (isFolder) folderCard(inflater, item) else templateCard(inflater, item, art[item.id], item.id in ticked, item.id in pinned)
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = cardHeight
                setMargins(half, half, half, half)
            }
            view.setOnClickListener { onTap(item) }
            view.setOnLongClickListener { onLongPress(item); true }
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }

    private fun folderCard(inflater: LayoutInflater, item: TemplateCard): View =
        inflater.inflate(R.layout.card_folder, container, false).apply {
            findViewById<TextView>(R.id.folderName).text = item.name
        }

    private fun templateCard(inflater: LayoutInflater, item: TemplateCard, art: Bitmap?, ticked: Boolean, pinned: Boolean): View =
        inflater.inflate(R.layout.card_template, container, false).apply {
            findViewById<TextView>(R.id.templateName).text = item.name
            findViewById<ImageView>(R.id.templatePreview).setImageBitmap(art)
            findViewById<ImageView>(R.id.templateTick).visibility = if (ticked) View.VISIBLE else View.GONE
            findViewById<ImageView>(R.id.pinBadge).visibility = if (pinned) View.VISIBLE else View.GONE
        }
}
