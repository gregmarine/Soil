package com.symmetricalpalmtree.soil.home

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.shell.AppEntry
import com.symmetricalpalmtree.soil.shell.Paging

/**
 * The installed apps as a grid of **fixed pages**: each app's own icon over its name, as many
 * columns and rows as the space holds, turned with previous and next. Nothing scrolls.
 *
 * The cells are rebuilt whenever the list, the page or the space changes. An app's icon is its
 * own artwork, shown as the screen renders it.
 */
class AppGrid(
    private val container: FrameLayout,
    private val onOpen: (AppEntry) -> Unit,
    /** Told the page now showing and how many there are, whenever either changes. */
    private val onPaged: (page: Int, pages: Int) -> Unit,
) {
    private var apps: List<AppEntry> = emptyList()
    private var page = 0

    private val res = container.resources
    private val cellMinWidth = res.getDimensionPixelSize(R.dimen.app_cell_min_width)
    private val cellHeight = res.getDimensionPixelSize(R.dimen.app_cell_height)
    private val iconSize = res.getDimensionPixelSize(R.dimen.app_icon_size)
    private val cellPadding = res.getDimensionPixelSize(R.dimen.app_cell_padding)

    init {
        container.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) container.post { render() }
        }
    }

    fun show(apps: List<AppEntry>) {
        this.apps = apps
        render()
    }

    fun previous() = turnTo(page - 1)
    fun next() = turnTo(page + 1)

    private fun turnTo(wanted: Int) {
        val to = Paging.clamp(wanted, apps.size, perPage())
        if (to == page) return
        page = to
        render()
    }

    private fun columns() = Paging.fit(container.width, cellMinWidth)
    private fun rows() = Paging.fit(container.height, cellHeight)
    private fun perPage() = columns() * rows()

    private fun render() {
        if (container.width == 0 || container.height == 0) return
        val columns = columns()
        val perPage = perPage()
        // The rows share the whole height, so a page fills the space it was counted for.
        val rowHeight = container.height / rows()
        page = Paging.clamp(page, apps.size, perPage)
        val shown = Paging.slice(apps, page, perPage)

        container.removeAllViews()
        val grid = LinearLayout(container.context).apply { orientation = LinearLayout.VERTICAL }
        for (row in shown.chunked(columns)) {
            val line = LinearLayout(container.context).apply { orientation = LinearLayout.HORIZONTAL }
            for (app in row) line.addView(cell(app), LinearLayout.LayoutParams(0, rowHeight, 1f))
            // A short last row is padded, so its cells keep the width of the rows above.
            repeat(columns - row.size) {
                line.addView(View(container.context), LinearLayout.LayoutParams(0, rowHeight, 1f))
            }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        }
        container.addView(
            grid,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        onPaged(page, Paging.pageCount(apps.size, perPage))
    }

    private fun cell(app: AppEntry): View {
        val context = container.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(cellPadding, cellPadding, cellPadding, cellPadding)
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            isClickable = true
            isFocusable = true
            contentDescription = app.label
            setOnClickListener { onOpen(app) }
            addView(
                AppCompatImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(app.icon)
                },
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
            addView(
                AppCompatTextView(context).apply {
                    text = app.label
                    textSize = 14f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(context.getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = cellPadding / 2
                },
            )
        }
    }
}
