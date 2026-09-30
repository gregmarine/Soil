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
 * | longer | yes | a swipe up — the firmware's refresh, left alone |
 * | longer | no | a swipe down — Soil's menu |
 *
 * A hold in place reads as a swipe down too: nothing distinguishes them.
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

    /** How long after the finger lifts the firmware's refresh broadcast is waited for. */
    const val SETTLE_MS = 150L

    /**
     * The firmware announces its side menu and its pull-down status bar with the same broadcast.
     * It is the side menu only if the right bar was touched this recently.
     */
    const val LEAK_WINDOW_MS = 1500L

    enum class Read { TAP, SWIPE_UP, SWIPE_DOWN }

    fun isBarKey(code: Int): Boolean =
        code == RIGHT_FIRST || code == RIGHT_SECOND || code == LEFT_FIRST || code == LEFT_SECOND ||
            code == DRAG || code == REFRESH || code == SLIDE

    fun read(heldMs: Long, refreshHeard: Boolean): Read = when {
        heldMs < TAP_MS -> Read.TAP
        refreshHeard -> Read.SWIPE_UP
        else -> Read.SWIPE_DOWN
    }

    /** Whether a "menu shown" broadcast, heard [sinceRightDownMs] after the right bar was
     *  touched, is the firmware's side menu slipping past the lock. */
    fun isSideMenuLeak(sinceRightDownMs: Long): Boolean = sinceRightDownMs in 0 until LEAK_WINDOW_MS
}
