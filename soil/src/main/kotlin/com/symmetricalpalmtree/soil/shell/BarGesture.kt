package com.symmetricalpalmtree.soil.shell

/**
 * **What the side bars can tell an app**, and how a touch on the right bar is read. Pure.
 *
 * The bars are a keyboard. Each holds one key down for as long as a finger is on it, and a second
 * key for a second finger; they carry **no position and no direction**. Direction comes from the
 * firmware alone: on a swipe up of the right bar it refreshes the screen and says so in a
 * broadcast. So a touch is read when the finger lifts, a moment later, by what was heard
 * meanwhile:
 *
 * | Held | Refresh heard | It was |
 * |---|---|---|
 * | under [TAP_MS] | — | a tap |
 * | [MAX_SWIPE_MS] or longer | — | a hold: a palm resting on the bar while writing |
 * | between | yes | a swipe up — the firmware's refresh, left alone |
 * | between | no | a swipe down — Soil's menu |
 *
 * A short rest of the hand reads as a swipe down still: the menu asks the app in front whether
 * the pen is active before it shows, and stays away while it is.
 */
object BarGesture {

    /** The right bar's first contact, and its second. */
    const val RIGHT_FIRST = 310
    const val RIGHT_SECOND = 309

    /** The left bar's first contact, and its second. */
    const val LEFT_FIRST = 301
    const val LEFT_SECOND = 300

    /** The top edge's pull-down, the firmware's refresh, and its slide. All the firmware's own. */
    const val DRAG = 290
    const val REFRESH = 291
    const val SLIDE = 292

    /** Shorter than this is a tap. */
    const val TAP_MS = 250L

    /** This long or longer is a hand resting on the bar, not a swipe. */
    const val MAX_SWIPE_MS = 1000L

    /** How long after the finger lifts the firmware's refresh broadcast is waited for. */
    const val SETTLE_MS = 150L

    /**
     * The firmware announces its side menu and its pull-down status bar with the same broadcast.
     * It is the side menu only if the right bar was touched this recently.
     */
    const val LEAK_WINDOW_MS = 1500L

    enum class Read { TAP, HOLD, SWIPE_UP, SWIPE_DOWN }

    fun isBarKey(code: Int): Boolean =
        code == RIGHT_FIRST || code == RIGHT_SECOND || code == LEFT_FIRST || code == LEFT_SECOND ||
            code == DRAG || code == REFRESH || code == SLIDE

    fun read(heldMs: Long, refreshHeard: Boolean): Read = when {
        heldMs < TAP_MS -> Read.TAP
        heldMs >= MAX_SWIPE_MS -> Read.HOLD
        refreshHeard -> Read.SWIPE_UP
        else -> Read.SWIPE_DOWN
    }

    /**
     * The right bar's first contact, paired down to up by the keys' own times. Pure.
     *
     * Over an app's paper the keys cross the seam one call each, from a pool of threads, so a down
     * and its up can arrive in either order. Read as they arrive, an up landing before its own
     * down pairs with the *previous* contact's down, and two short brushes of a palm on the edge
     * strip a few hundred milliseconds apart read as one swipe down: the menu, with no swipe.
     * So an up is paired only with a down still open and not later than it, and a down older than
     * the last up heard is stale.
     */
    class RightBar {
        /** When the open contact went down, or the last one did once it is closed. */
        var downAt = 0L
            private set
        private var open = false
        private var lastUpAt = Long.MIN_VALUE

        /** A first down (repeat 0). False when it is stale: an up later than it was already heard. */
        fun down(eventTime: Long): Boolean {
            if (eventTime < lastUpAt) return false
            downAt = eventTime
            open = true
            return true
        }

        /** An up: how long its contact was held, or **null** when there is no open down for it. */
        fun up(eventTime: Long): Long? {
            if (eventTime > lastUpAt) lastUpAt = eventTime
            if (!open || eventTime < downAt) return null
            open = false
            return eventTime - downAt
        }
    }

    /** Whether a "menu shown" broadcast, heard [sinceRightDownMs] after the right bar was
     *  touched, is the firmware's side menu slipping past the lock. */
    fun isSideMenuLeak(sinceRightDownMs: Long): Boolean = sinceRightDownMs in 0 until LEAK_WINDOW_MS
}
