package com.symmetricalpalmtree.soil.home

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.symmetricalpalmtree.soil.paper.core.SwipeMath
import kotlin.math.abs

/**
 * A frame whose pages turn on a **sideways swipe**, as well as by whatever buttons turn them.
 *
 * The swipe is the Scratch Pad's own ([SwipeMath.flip]), so the hand learns one gesture: the same
 * travel across the same fraction of the surface means the same thing everywhere. Leftward is
 * the next page, rightward the one before.
 *
 * What is inside still takes its taps and long presses. A touch is taken from it only once it
 * has moved sideways further than a tap can, and further sideways than up or down. Nothing
 * follows the finger and nothing slides: the page changes once, when the finger lifts.
 */
class PageSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null

    private val config = ViewConfiguration.get(context)
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = abs(ev.x - downX)
                if (dx > config.scaledTouchSlop && dx > abs(ev.y - downY)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // A touch that landed between the cells is ours from the start.
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> velocity?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(ev)
                velocity?.computeCurrentVelocity(1000)
                val flip = SwipeMath.flip(
                    dx = ev.x - downX,
                    dy = ev.y - downY,
                    vx = velocity?.xVelocity ?: 0f,
                    width = width.toFloat(),
                    minVelocity = config.scaledMinimumFlingVelocity * SwipeMath.MIN_VELOCITY_MULT,
                )
                end()
                when (flip) {
                    SwipeMath.FORWARD -> onNext?.invoke()
                    SwipeMath.BACK -> onPrevious?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> end()
        }
        return true
    }

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun end() {
        velocity?.recycle()
        velocity = null
    }
}
