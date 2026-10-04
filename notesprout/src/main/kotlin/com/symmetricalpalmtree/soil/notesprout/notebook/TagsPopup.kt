package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.paper.chrome.AnchoredBar

/**
 * The tag button's sub-bar, hung under it: the notebook's three tag doors. **Tag notebook** and
 * **Tag page** open Soil's tag screen with the field focused, on the notebook or on the page whose
 * ink is on the paper; **Manage** opens the notebook and every page of it. Three doors, because a
 * tag has to land on something, and which of the two things it lands on is the one question the
 * button cannot answer for you. Icon-only, hinted on a long press.
 */
class TagsPopup(
    root: ViewGroup,
    bar: LinearLayout,
    anchor: View,
    bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onTagNotebook: () -> Unit,
    private val onTagPage: () -> Unit,
    private val onManage: () -> Unit,
) {
    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        this.bar.addButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_notebook, ctx.getString(R.string.tag_notebook_action)) { releaseRender(); onTagNotebook() }
        this.bar.addButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page, ctx.getString(R.string.tag_page_action)) { releaseRender(); onTagPage() }
        this.bar.addButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_list, ctx.getString(R.string.tag_manage_action)) { releaseRender(); onManage() }
    }

    fun show(anchor: View? = null): Boolean = if (anchor == null) bar.show() else bar.show(anchor)
    fun hide() = bar.hide()
    fun rects(): List<Rect> = bar.rects()
    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)
}
