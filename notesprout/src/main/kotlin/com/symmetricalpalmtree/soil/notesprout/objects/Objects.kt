package com.symmetricalpalmtree.soil.notesprout.objects

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.store.Row

/**
 * The things placed among a page's ink, as the screen holds them. Each is one row of the notebook
 * table, of its own type; the mappers here read a row into one and are pure, so every rule about
 * a malformed row is JVM-tested. **A bad row is a dropped object, never a lost page.**
 */

/**
 * A heading: hash-prefixed Markdown (`"## Meeting notes"`), the authoritative level (the `flags`
 * column; the prefix is derived from it at every write, never the other way), and its box in page
 * px. The box was measured at write time and is measured again on every load.
 */
data class Heading(
    val id: String,
    val text: String,
    /** 1..6, authoritative. */
    val level: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Z-order among the page's headings. */
    val order: Int,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)
    fun translated(dx: Float, dy: Float): Heading = copy(x = x + dx, y = y + dy)
}

/**
 * A text object: raw Markdown source and its box. The top-left is **authored** and stays put
 * through an edit; the size is **derived**, measured at write time and again on every load, so a
 * text authored on one device measures right on another.
 */
data class PageText(
    val id: String,
    /** Always non-blank: a blank text object never exists. */
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val order: Int,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)
    fun translated(dx: Float, dy: Float): PageText = copy(x = x + dx, y = y + dy)
}

/**
 * A sticky note: the **icon box** on the page and the note's content size. The page knows the
 * note by its icon alone; its content is `stroke` rows parented to [id] in the note's own space,
 * `(0,0)` at the content's top-left, and never draws on the page. A drag moves the one row; the
 * children are not in page space at all.
 */
data class PageSticky(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** The content size in px, fixed at creation to the creating device's editor paper. */
    val contentW: Int,
    val contentH: Int,
    val order: Int,
    /** The content, when a caller read it: an undo snapshot, the editor's open. Empty on the
     *  page's own load. */
    val strokes: List<Stroke> = emptyList(),
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)
    val childIds: List<String> get() = strokes.map { it.id }
    fun translated(dx: Float, dy: Float): PageSticky = copy(x = x + dx, y = y + dy)
}

/** The `flags` word of a sticky row: bits 0–19 the content width, bits 20–39 the height, in px. */
object StickyFlags {
    const val MAX = 0xFFFFF
    private const val MASK = 0xFFFFFL
    private const val H_SHIFT = 20

    fun pack(contentW: Int, contentH: Int): Long =
        (contentW.coerceIn(0, MAX).toLong()) or (contentH.coerceIn(0, MAX).toLong() shl H_SHIFT)

    fun contentW(flags: Long?): Int = ((flags ?: 0L) and MASK).toInt()
    fun contentH(flags: Long?): Int = ((flags ?: 0L) shr H_SHIFT and MASK).toInt()
}

/**
 * The object rows read back. The row shape is [NotebookSql.selectObjects]'s:
 * `id, type, "order", text, refId, x, y, width, height, flags`.
 */
object ObjectRows {

    fun toHeading(row: Row): Heading? {
        return try {
        val text = row.textOrNull("text") ?: return null
        Heading(
            id = row.text("id"), text = text,
            level = (row.longOrNull("flags") ?: 1L).coerceIn(1L, 6L).toInt(),
            x = row.realOrNull("x")?.toFloat() ?: 0f, y = row.realOrNull("y")?.toFloat() ?: 0f,
            width = row.realOrNull("width")?.toFloat() ?: 0f, height = row.realOrNull("height")?.toFloat() ?: 0f,
            order = row.long("order").toInt(),
        )
        } catch (_: Exception) {
            null
        }
    }

    fun toText(row: Row): PageText? {
        return try {
        val text = row.textOrNull("text") ?: return null
        if (text.isBlank()) return null
        val x = row.realOrNull("x")?.toFloat() ?: 0f
        val y = row.realOrNull("y")?.toFloat() ?: 0f
        val w = row.realOrNull("width")?.toFloat() ?: 0f
        val h = row.realOrNull("height")?.toFloat() ?: 0f
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        PageText(id = row.text("id"), text = text, x = x, y = y, width = w, height = h, order = row.long("order").toInt())
        } catch (_: Exception) {
            null
        }
    }

    fun toSticky(row: Row): PageSticky? {
        return try {
        val x = row.realOrNull("x")?.toFloat() ?: return null
        val y = row.realOrNull("y")?.toFloat() ?: return null
        val w = row.realOrNull("width")?.toFloat() ?: return null
        val h = row.realOrNull("height")?.toFloat() ?: return null
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        val flags = row.longOrNull("flags")
        PageSticky(
            id = row.text("id"), x = x, y = y, width = w, height = h,
            contentW = StickyFlags.contentW(flags), contentH = StickyFlags.contentH(flags),
            order = row.long("order").toInt(),
        )
        } catch (_: Exception) {
            null
        }
    }

    /** The icon's edge at creation, in dp. */
    const val STICKY_ICON_DP = 72f
}
