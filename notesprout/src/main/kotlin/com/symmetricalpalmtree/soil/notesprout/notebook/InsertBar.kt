package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.objects.ShapeType
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
    /** The buttons' order is this order; a new kind is appended, never inserted. */
    enum class Kind { HEADING, TEXT, STICKY, RECTANGLE, ELLIPSE, TRIANGLE, LINE, ARROW, STAR }

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
        fun shapeType(kind: Kind): ShapeType? = when (kind) {
            Kind.HEADING, Kind.TEXT, Kind.STICKY -> null
            Kind.RECTANGLE -> ShapeType.RECTANGLE
            Kind.ELLIPSE -> ShapeType.ELLIPSE
            Kind.TRIANGLE -> ShapeType.TRIANGLE
            Kind.LINE -> ShapeType.LINE
            Kind.ARROW -> ShapeType.ARROW
            Kind.STAR -> ShapeType.STAR
        }

        private fun iconOf(kind: Kind): Int = when (kind) {
            Kind.HEADING -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_heading
            Kind.TEXT -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_text_recognition
            Kind.STICKY -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_sticker_2
            Kind.RECTANGLE -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_shape_rectangle
            Kind.ELLIPSE -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_shape_ellipse
            Kind.TRIANGLE -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_shape_triangle
            Kind.LINE -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_shape_line
            Kind.ARROW -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_shape_arrow
            Kind.STAR -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_shape_star
        }

        private fun hintOf(kind: Kind): Int = when (kind) {
            Kind.HEADING -> R.string.insert_heading
            Kind.TEXT -> R.string.insert_text
            Kind.STICKY -> R.string.insert_sticky
            Kind.RECTANGLE -> R.string.insert_rectangle
            Kind.ELLIPSE -> R.string.insert_ellipse
            Kind.TRIANGLE -> R.string.insert_triangle
            Kind.LINE -> R.string.insert_line
            Kind.ARROW -> R.string.insert_arrow
            Kind.STAR -> R.string.insert_star
        }
    }
}
