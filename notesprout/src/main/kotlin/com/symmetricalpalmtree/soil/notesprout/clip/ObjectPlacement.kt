package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.gpaper.core.model.Bounds

/**
 * Where a pasted set of objects lands: pure arithmetic over a box, a target and a page. Two ways
 * in, one rule out: [centredOn] a pen tap (a tap is an aim, not a corner), or [atSource] for the
 * popup's Paste (no tap to aim at, so a same-size page reproduces the layout). Both clamp onto
 * the page, silently. Content larger than the page on an axis pastes from that edge. A
 * non-positive page size means unknown and does not clamp on that axis.
 */
object ObjectPlacement {

    data class Offset(val dx: Float, val dy: Float) {
        companion object {
            val NONE = Offset(0f, 0f)
        }
    }

    fun centredOn(box: Bounds, tapX: Float, tapY: Float, pageWidth: Float, pageHeight: Float): Offset =
        clampedTo(box, tapX - box.width / 2f, tapY - box.height / 2f, pageWidth, pageHeight)

    fun atSource(box: Bounds, pageWidth: Float, pageHeight: Float): Offset =
        clampedTo(box, box.left, box.top, pageWidth, pageHeight)

    private fun clampedTo(box: Bounds, targetLeft: Float, targetTop: Float, pageWidth: Float, pageHeight: Float): Offset = Offset(
        dx = axis(box.left, box.width, targetLeft, pageWidth),
        dy = axis(box.top, box.height, targetTop, pageHeight),
    )

    /** One axis: the shift from [from] to [target], pulled back from the far edge first, then off the near edge. */
    private fun axis(from: Float, size: Float, target: Float, page: Float): Float {
        if (!from.isFinite() || !size.isFinite() || !target.isFinite()) return 0f
        var shift = target - from
        if (page <= 0f) return shift
        if (size > page) return -from
        val overshoot = (from + size + shift) - page
        if (overshoot > 0f) shift -= overshoot
        val undershoot = from + shift
        if (undershoot < 0f) shift -= undershoot
        return shift
    }
}
