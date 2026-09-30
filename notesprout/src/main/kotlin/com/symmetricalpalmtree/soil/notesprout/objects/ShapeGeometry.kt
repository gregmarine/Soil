package com.symmetricalpalmtree.soil.notesprout.objects

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The outline of a [PageShape] in page coordinates, pure geometry the renderer, the export, the
 * hit test and the lasso box all read, so a shape can never draw in one place and be hit in
 * another. Local space is the un-rotated box centred on the origin; the rotation is applied last,
 * about the centre, then the translation to the centre. **A rotated shape is hit by its box**, not
 * its outline.
 */
object ShapeGeometry {

    const val STAR_INNER_RATIO = 0.5f
    const val ARROW_ARM_FRACTION = 0.3f
    const val ARROW_ARM_MAX_PX = 48f
    private const val ARROW_ARM_DEG = 150.0
    private const val HIT_PAD_DP = 4f

    data class Pt(val x: Float, val y: Float)

    /** One polyline of the outline; [closed] joins the last point back to the first. */
    data class Poly(val points: List<Pt>, val closed: Boolean)

    /** The outline as polylines. The ellipse answers its rotated box's corners (an extent); [pathFor]
     *  draws the true oval. */
    fun outline(s: PageShape): List<Poly> {
        val hw = s.width / 2f
        val hh = s.height / 2f
        val local: List<Poly> = when (s.type) {
            ShapeType.RECTANGLE, ShapeType.ELLIPSE -> listOf(Poly(listOf(Pt(-hw, -hh), Pt(hw, -hh), Pt(hw, hh), Pt(-hw, hh)), closed = true))
            ShapeType.TRIANGLE -> listOf(Poly(listOf(Pt(0f, -hh), Pt(hw, hh), Pt(-hw, hh)), closed = true))
            ShapeType.STAR -> listOf(Poly(starPoints(hw, hh, s.pointCount), closed = true))
            ShapeType.LINE -> listOf(Poly(listOf(Pt(-hw, 0f), Pt(hw, 0f)), closed = false))
            ShapeType.ARROW -> {
                val arm = min(s.width * ARROW_ARM_FRACTION, ARROW_ARM_MAX_PX)
                val a = Math.toRadians(ARROW_ARM_DEG)
                val ax = (arm * cos(a)).toFloat()
                val ay = (arm * sin(a)).toFloat()
                listOf(
                    Poly(listOf(Pt(-hw, 0f), Pt(hw, 0f)), closed = false),
                    Poly(listOf(Pt(hw + ax, -ay), Pt(hw, 0f), Pt(hw + ax, ay)), closed = false),
                )
            }
        }
        val box = ShapeBox.toBox(s)
        return local.map { poly -> Poly(poly.points.map { p -> box.toPage(p.x, p.y).let { (x, y) -> Pt(x, y) } }, poly.closed) }
    }

    /** The axis-aligned box of the rotated outline, inflated by `max(strokeWidth / 2, 4 dp)`: what
     *  the hit targets report and the lasso box shows. */
    fun aabb(s: PageShape, density: Float): Bounds =
        tightBounds(s).inflated(max(s.strokeWidth / 2f, HIT_PAD_DP * density))

    /** The rotated outline's point-tight box; the ellipse's is analytic. */
    fun tightBounds(s: PageShape): Bounds {
        if (s.type == ShapeType.ELLIPSE) {
            val rad = Math.toRadians(s.rotationDeg.toDouble())
            val a = s.width / 2f
            val b = s.height / 2f
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val ex = sqrt(a * a * c * c + b * b * sn * sn)
            val ey = sqrt(a * a * sn * sn + b * b * c * c)
            return Bounds(s.cx - ex, s.cy - ey, s.cx + ex, s.cy + ey)
        }
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var btm = Float.NEGATIVE_INFINITY
        for (poly in outline(s)) for (p in poly.points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > btm) btm = p.y
        }
        if (l > r || t > btm) return Bounds(s.cx, s.cy, s.cx, s.cy)
        return Bounds(l, t, r, btm)
    }

    /** The path of [outline], to stroke, never to fill. */
    fun pathFor(s: PageShape): Path {
        val path = Path()
        if (s.type == ShapeType.ELLIPSE) {
            val hw = s.width / 2f
            val hh = s.height / 2f
            path.addOval(RectF(-hw, -hh, hw, hh), Path.Direction.CW)
            val m = Matrix()
            m.postRotate(s.rotationDeg)
            m.postTranslate(s.cx, s.cy)
            path.transform(m)
            return path
        }
        for (poly in outline(s)) {
            poly.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (poly.closed) path.close()
        }
        return path
    }

    private fun starPoints(hw: Float, hh: Float, count: Int): List<Pt> {
        val n = count.coerceIn(ShapeFlags.MIN_POINTS, ShapeFlags.MAX_POINTS)
        val pts = ArrayList<Pt>(n * 2)
        val step = Math.PI / n
        for (i in 0 until n * 2) {
            val ang = -Math.PI / 2 + i * step
            val ratio = if (i % 2 == 0) 1f else STAR_INNER_RATIO
            pts += Pt((hw * ratio * cos(ang)).toFloat(), (hh * ratio * sin(ang)).toFloat())
        }
        return pts
    }
}

/** A [PageShape]'s five geometry numbers are an [OrientedBox]; the one place they meet. A rotation
 *  coming back from the engine is normalised to the row's tenth of a degree. */
object ShapeBox {
    fun toBox(shape: PageShape): OrientedBox = OrientedBox(shape.cx, shape.cy, shape.width, shape.height, shape.rotationDeg)

    fun applied(shape: PageShape, box: OrientedBox): PageShape = shape.copy(
        cx = box.cx, cy = box.cy, width = box.w, height = box.h, rotationDeg = ShapeFlags.normalizeDeg(box.rotationDeg),
    )
}

/**
 * What each shape **is** the moment it is placed. The closed shapes land as a 72 dp square, three
 * of them aspect-locked because a square, a circle and a regular star are what those shapes are
 * for; a line and an arrow land half the page wide and one px tall, unlocked, because their
 * outline is a centre line.
 */
object ShapeDefaults {
    const val CLOSED_SIZE_DP = 72f
    const val OPEN_WIDTH_FRACTION = 0.5f
    const val OPEN_HEIGHT_PX = 1f

    /** The floor the transform mode clamps every side at, in dp. */
    const val MIN_SIZE_DP = 24f

    fun at(id: String, type: ShapeType, pageWidth: Float, pageHeight: Float, density: Float): PageShape {
        val open = isOpen(type)
        return PageShape(
            id = id, type = type, cx = pageWidth / 2f, cy = pageHeight / 2f,
            width = if (open) OPEN_WIDTH_FRACTION * pageWidth else CLOSED_SIZE_DP * density,
            height = if (open) OPEN_HEIGHT_PX else CLOSED_SIZE_DP * density,
            strokeWidth = ObjectRows.DEFAULT_STROKE_WIDTH_PX, rotationDeg = 0f,
            aspectLocked = locked(type), pointCount = ShapeFlags.DEFAULT_POINTS, order = 0,
        )
    }

    fun isOpen(type: ShapeType): Boolean = type == ShapeType.LINE || type == ShapeType.ARROW

    fun locked(type: ShapeType): Boolean = when (type) {
        ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.STAR -> true
        ShapeType.TRIANGLE, ShapeType.LINE, ShapeType.ARROW -> false
    }
}
