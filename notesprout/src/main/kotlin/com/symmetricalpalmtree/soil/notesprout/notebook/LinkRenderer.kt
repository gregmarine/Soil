package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * The paints one drawing of a page needs, built by the caller that draws and kept to itself: a
 * `Paint` and a `Drawable` carry mutable state, and a page may be drawn off Main (a link's
 * composite, a picker preview) while the engine's own renderers draw on it.
 *
 * [stickyIcon] is nullable, and its absence is the whole failure mode: a page draws without its
 * note icons rather than not at all.
 */
class PagePaints(val heading: TextPaint, val text: TextPaint, val stickyIcon: Drawable?) {
    companion object {
        fun of(scaledDensity: Float, stickyIcon: Drawable?): PagePaints =
            PagePaints(HeadingRenderer.basePaint(scaledDensity), TextRenderer.basePaint(scaledDensity), stickyIcon)
    }
}

/**
 * **The page's content in the paper's own layering**, drawn once, here, so a link's composite
 * and a picker's preview show a page the way the screen does: loose headings and texts; each
 * link in z-order (its headings, texts, ink, then its sticky icons); loose sticky icons; loose
 * ink last. A sticky's content appears nowhere on a page. No chrome: neither caller draws it.
 */
object PageDraw {

    fun drawContent(canvas: Canvas, content: PageContent, density: Float, paints: PagePaints) {
        for (h in content.headings) HeadingRenderer.drawHeading(canvas, h, density, paints.heading)
        for (t in content.texts) TextRenderer.drawText(canvas, t, density, paints.text)
        for (l in content.links) drawWrapped(canvas, l, density, paints)
        val icon = paints.stickyIcon
        if (icon != null) for (s in content.stickies) StickyRenderer.drawSticky(canvas, s, icon)
        StrokeRasterizer.draw(canvas, content.strokes.map { it.second })
    }

    /** What a link wraps, in the page's order, at page coordinates. */
    fun drawWrapped(canvas: Canvas, l: PageLink, density: Float, paints: PagePaints) {
        for (h in l.headings) HeadingRenderer.drawHeading(canvas, h, density, paints.heading)
        for (t in l.texts) TextRenderer.drawText(canvas, t, density, paints.text)
        StrokeRasterizer.draw(canvas, l.strokes)
        val icon = paints.stickyIcon
        if (icon != null) for (s in l.stickies) StickyRenderer.drawSticky(canvas, s, icon)
    }
}

/**
 * A link's composite: what it wraps, rendered at 1:1 page px into one bitmap the size of the
 * link's bounds plus a margin, through the same recipes the page draws with, so a wrap changes
 * nothing about how the content looks. The underline is not baked in: the renderer draws it
 * live, so a chrome edit never rebuilds the composite. The composite is translation-invariant,
 * so a move never rebuilds it either; only the wrapped set changes it.
 */
object LinkComposite {

    /**
     * The margin beyond the bounds on every side: a stroke's bounds are the tight bounds of its
     * points, but its ink overhangs them by half its width and its round cap, so a bitmap cut at
     * the bounds would shear the outermost strokes. Zero for a link with no ink.
     */
    fun padOf(link: PageLink): Int {
        val widest = link.strokes.maxOfOrNull { it.width } ?: 0f
        if (!(widest > 0f)) return 0
        return ceil(widest / 2f).toInt() + 1
    }

    /** The bitmap size [build] makes: the bounds plus [padOf] each side, capped. The cache's
     *  reuse check compares against this, never the raw bounds. */
    fun sizeOf(link: PageLink): Pair<Int, Int> {
        val pad = padOf(link)
        return ceil(link.width).toInt().plus(2 * pad).coerceAtMost(MAX_EDGE_PX) to
            ceil(link.height).toInt().plus(2 * pad).coerceAtMost(MAX_EDGE_PX)
    }

    /** Null when the link has no drawable size or the bitmap would not allocate: the renderer
     *  draws a dashed placeholder instead. ARGB, never erased: the paper shows through. */
    fun build(link: PageLink, density: Float, paints: PagePaints): Bitmap? {
        val (w, h) = sizeOf(link)
        if (w < 1 || h < 1) return null
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a ${w}x$h composite would not allocate")
            return null
        }
        val pad = padOf(link)
        val canvas = Canvas(bmp)
        canvas.translate(pad - link.x, pad - link.y)
        PageDraw.drawWrapped(canvas, link, density, paints)
        return bmp
    }

    /** A wrap cannot outgrow a page by much; a foreign file's bounds are untrusted input. */
    const val MAX_EDGE_PX = 4_096

    private const val TAG = "LinkComposite"
}

/**
 * The page's links in g-paper's committed layer, **below the ink**: fresh ink over a link stays
 * visible on top. Each link draws its composite (or a dashed placeholder when none could be
 * built), then its underline, live, as whole pixels of black across the bottom of its bounds.
 *
 * [update] is the one way in, on Main: it swaps the working copy and reconciles the composite
 * cache, reusing a bitmap whose size still matches (a move), building the rest, dropping the
 * departed. [prebuild] does the raster work off Main ahead of a page load, so a link-heavy flip
 * never builds bitmaps inside the frame that paints the page.
 */
class LinkRenderer(private val density: Float, scaledDensity: Float, stickyIcon: Drawable) : ContentRenderer {

    override val layer = ContentLayer.BELOW_STROKES

    var links: List<PageLink> = emptyList()
        private set

    private val composites = HashMap<String, Bitmap>()
    private val paints = PagePaints.of(scaledDensity, stickyIcon)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val underline = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.BLACK
        pathEffect = DashPathEffect(floatArrayOf(DASH_PX, DASH_PX), 0f)
    }

    /** The composites [update] would have to build, built off Main. The set is decided on the
     *  caller's thread against the live cache; only the raster work moves. */
    suspend fun prebuild(links: List<PageLink>): Map<String, Bitmap> {
        val todo = links.filter { l ->
            val cached = composites[l.id]
            val (w, h) = LinkComposite.sizeOf(l)
            cached == null || cached.width != w || cached.height != h
        }
        if (todo.isEmpty()) return emptyMap()
        return withContext(Dispatchers.Default) {
            buildMap { for (l in todo) LinkComposite.build(l, density, paints)?.let { put(l.id, it) } }
        }
    }

    fun update(links: List<PageLink>, prebuilt: Map<String, Bitmap> = emptyMap()) {
        this.links = links
        val wanted = links.associateBy { it.id }
        composites.keys.retainAll(wanted.keys)
        for (l in links) {
            val (w, h) = LinkComposite.sizeOf(l)
            val fresh = prebuilt[l.id]
            if (fresh != null && fresh.width == w && fresh.height == h) {
                composites[l.id] = fresh
                continue
            }
            val cached = composites[l.id]
            if (cached != null && cached.width == w && cached.height == h) continue
            val built = LinkComposite.build(l, density, paints)
            if (built != null) composites[l.id] = built else composites.remove(l.id)
        }
    }

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (l in links) if (l.id !in excludedContentIds) drawLink(canvas, l)
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val l = links.firstOrNull { it.id == contentId } ?: return false
        drawLink(canvas, l)
        return true
    }

    override fun hitTargets(): List<HitTarget> = links.map { HitTarget(it.id, it.bounds) }

    private fun drawLink(canvas: Canvas, l: PageLink) {
        val bmp = composites[l.id]
        if (bmp != null && !bmp.isRecycled) {
            val pad = LinkComposite.padOf(l).toFloat()
            canvas.drawBitmap(bmp, l.x - pad, l.y - pad, bitmapPaint)
        } else {
            canvas.drawRect(l.x + 0.5f, l.y + 0.5f, l.x + l.width - 0.5f, l.y + l.height - 0.5f, placeholder)
        }
        if (l.chrome == LinkPayload.CHROME_UNDERLINE) {
            // Whole pixels, filled: a 1 dp line lands on a half pixel and comes out faint or
            // doubled, and an underline is read as much as the words over it.
            val px = max(round(density), 2f)
            val bottom = floor(l.y + l.height)
            canvas.drawRect(l.x, bottom - px, l.x + l.width, bottom, underline)
        }
    }

    private companion object {
        const val DASH_PX = 6f
    }
}
