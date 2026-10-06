package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.paper.chrome.AnchoredBar

/**
 * The Insert button's sub-bar: what a page can be given that is not ink, left to right, always in
 * this order. **Insert is a command, not a tool**: a pick places an object and lands it selected;
 * the armed tool is what it was. The screen owns when the bar closes and unions [rects] into the
 * exclusion rects, because a pen landing on a floating bar must never ink.
 */
class InsertBar(
    root: ViewGroup,
    bar: LinearLayout,
    anchor: View,
    bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onInsert: (Kind) -> Unit,
) {
    /** The buttons' order is this order; a new kind is appended, never inserted. The shapes were
     *  set aside on 2026-09-30 (BACKLOG.md). */
    enum class Kind { HEADING, TEXT, STICKY, BIBLE }

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    val isShowing: Boolean get() = bar.isShowing

    init {
        val ctx = root.context
        for (kind in Kind.entries) this.bar.addButton(iconOf(kind), ctx.getString(hintOf(kind))) { releaseRender(); onInsert(kind) }
    }

    fun show(anchor: View? = null): Boolean = if (anchor == null) bar.show() else bar.show(anchor)
    fun hide() = bar.hide()
    fun rects(): List<Rect> = bar.rects()
    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    companion object {
        private fun iconOf(kind: Kind): Int = when (kind) {
            Kind.HEADING -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_heading
            Kind.TEXT -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_text_recognition
            Kind.STICKY -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_sticker_2
            Kind.BIBLE -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_book
        }

        private fun hintOf(kind: Kind): Int = when (kind) {
            Kind.HEADING -> R.string.insert_heading
            Kind.TEXT -> R.string.insert_text
            Kind.STICKY -> R.string.insert_sticky
            Kind.BIBLE -> R.string.insert_bible
        }
    }
}
