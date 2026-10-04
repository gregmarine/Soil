package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.paper.chrome.AnchoredBar

/**
 * The lasso button's popup: a small bordered bar hung under the **already-armed** lasso button,
 * holding **Paste** and **Clear** for the object clipboard. Tap-to-place is invisible, so the two
 * acts that have no gesture (a paste at the source coordinates, and throwing the clipboard away)
 * live here, under the one control that already means "the clipboard is in play". It opens only
 * while the clipboard holds objects. The screen owns when it closes.
 */
class LassoPopup(
    root: ViewGroup,
    bar: LinearLayout,
    anchor: View,
    bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onPaste: () -> Unit,
    private val onClear: () -> Unit,
) {
    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        this.bar.addButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_clipboard, ctx.getString(R.string.paste_objects_action)) { releaseRender(); onPaste() }
        this.bar.addButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, ctx.getString(R.string.clear_clipboard_action)) { releaseRender(); onClear() }
    }

    fun show(anchor: View? = null): Boolean = if (anchor == null) bar.show() else bar.show(anchor)
    fun hide() = bar.hide()
    fun rects(): List<Rect> = bar.rects()
    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)
}
