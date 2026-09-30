package com.symmetricalpalmtree.soil.home

import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.shell.Paging
import java.util.Date

/**
 * The library's items as **fixed pages** of rows: each item's name over its page count and the
 * day it was last written, as many rows as the space holds, turned with previous and next. Nothing scrolls.
 *
 * The rows are rebuilt whenever the list, the page or the space changes.
 */
class ItemList(
    private val container: FrameLayout,
    private val onOpen: (Item) -> Unit,
    /** Told the page now showing and how many there are, whenever either changes. */
    private val onPaged: (page: Int, pages: Int) -> Unit,
) {
    private var items: List<Item> = emptyList()
    private var page = 0

    private val res = container.resources
    private val rowHeight = res.getDimensionPixelSize(R.dimen.library_row_height)
    private val rowPadding = res.getDimensionPixelSize(R.dimen.library_row_padding)
    private val hairline = Math.round(res.displayMetrics.density).coerceAtLeast(1)
    private val dateFormat = DateFormat.getMediumDateFormat(container.context)

    init {
        container.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) container.post { render() }
        }
    }

    fun show(items: List<Item>) {
        this.items = items
        // Never inside a layout pass: a view added there is not drawn.
        container.post { render() }
    }

    fun previous() = turnTo(page - 1)
    fun next() = turnTo(page + 1)

    private fun turnTo(wanted: Int) {
        val to = Paging.clamp(wanted, items.size, perPage())
        if (to == page) return
        page = to
        render()
    }

    private fun perPage() = Paging.fit(container.height, rowHeight)

    private fun render() {
        if (container.width == 0 || container.height == 0) return
        val perPage = perPage()
        page = Paging.clamp(page, items.size, perPage)
        container.removeAllViews()
        val list = LinearLayout(container.context).apply { orientation = LinearLayout.VERTICAL }
        for (item in Paging.slice(items, page, perPage)) {
            list.addView(row(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight - hairline))
            list.addView(
                View(container.context).apply { setBackgroundColor(ink()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairline),
            )
        }
        container.addView(
            list,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        onPaged(page, Paging.pageCount(items.size, perPage))
    }

    private fun row(item: Item): View {
        val context = container.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(rowPadding, 0, rowPadding, 0)
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            isClickable = true
            isFocusable = true
            contentDescription = item.name
            setOnClickListener { onOpen(item) }
            addView(
                AppCompatTextView(context).apply {
                    text = item.name
                    textSize = 18f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ink())
                },
            )
            addView(
                AppCompatTextView(context).apply {
                    text = context.getString(
                        R.string.library_row_detail,
                        context.resources.getQuantityString(R.plurals.library_pages, item.pageCount, item.pageCount),
                        dateFormat.format(Date(item.updatedAt)),
                    )
                    textSize = 14f
                    maxLines = 1
                    setTextColor(ink())
                },
            )
        }
    }

    private fun ink() = container.context.getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
}
