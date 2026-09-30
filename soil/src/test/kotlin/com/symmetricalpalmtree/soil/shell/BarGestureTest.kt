package com.symmetricalpalmtree.soil.shell

import com.symmetricalpalmtree.soil.shell.BarGesture.Read
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarGestureTest {

    @Test
    fun aShortTouchIsATap_whateverWasHeard() {
        assertEquals(Read.TAP, BarGesture.read(heldMs = 120, refreshHeard = false))
        assertEquals(Read.TAP, BarGesture.read(heldMs = 249, refreshHeard = true))
    }

    @Test
    fun aLongerTouchWithTheRefreshHeardIsASwipeUp() {
        assertEquals(Read.SWIPE_UP, BarGesture.read(heldMs = 250, refreshHeard = true))
        assertEquals(Read.SWIPE_UP, BarGesture.read(heldMs = 900, refreshHeard = true))
    }

    @Test
    fun aLongerTouchWithNothingHeardIsASwipeDown() {
        assertEquals(Read.SWIPE_DOWN, BarGesture.read(heldMs = 250, refreshHeard = false))
        assertEquals(Read.SWIPE_DOWN, BarGesture.read(heldMs = 999, refreshHeard = false))
    }

    @Test
    fun `a hand resting on the bar is a hold, whatever the firmware said`() {
        assertEquals(Read.HOLD, BarGesture.read(heldMs = 1_000, refreshHeard = false))
        assertEquals(Read.HOLD, BarGesture.read(heldMs = 5_000, refreshHeard = false))
        assertEquals(Read.HOLD, BarGesture.read(heldMs = 5_000, refreshHeard = true))
    }

    @Test
    fun theBarsAndTheFirmwaresOwnKeysAreBarKeys_andNothingElseIs() {
        for (code in listOf(300, 301, 309, 310, 290, 291, 292)) assertTrue("$code", BarGesture.isBarKey(code))
        for (code in listOf(4, 82, 66, 289, 293, 299, 302, 308, 311)) assertFalse("$code", BarGesture.isBarKey(code))
    }

    /** The same broadcast announces the pull-down status bar, which is never Soil's. */
    @Test
    fun aMenuShownLongAfterTheBarWasTouchedIsTheStatusBar() {
        assertTrue(BarGesture.isSideMenuLeak(0))
        assertTrue(BarGesture.isSideMenuLeak(1_499))
        assertFalse(BarGesture.isSideMenuLeak(1_500))
        assertFalse(BarGesture.isSideMenuLeak(60_000))
    }
}
