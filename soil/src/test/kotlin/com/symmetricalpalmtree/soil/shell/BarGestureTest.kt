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

    @Test
    fun `a down and its up pair as they were made`() {
        val bar = BarGesture.RightBar()
        assertTrue(bar.down(1_000))
        assertEquals(400L, bar.up(1_400))
    }

    @Test
    fun `an up arriving before its own down never pairs with the contact before it`() {
        // Two brushes of a palm: 1000..1050 and 1500..1510. The second's up crosses the seam first.
        val bar = BarGesture.RightBar()
        assertTrue(bar.down(1_000))
        assertEquals(50L, bar.up(1_050))
        assertEquals(null, bar.up(1_510))   // no open down: not "held 510 ms", a swipe down
        assertFalse(bar.down(1_500))        // stale: its up was already heard
        assertTrue(bar.down(2_000))
        assertEquals(300L, bar.up(2_300))
    }

    @Test
    fun `an up older than the open down is not its up`() {
        // 1000..1100, then 1500..; the first up arrives after the second down.
        val bar = BarGesture.RightBar()
        assertTrue(bar.down(1_000))
        assertTrue(bar.down(1_500))
        assertEquals(null, bar.up(1_100))
        assertEquals(200L, bar.up(1_700))
    }

    @Test
    fun `an up with no down at all is ignored`() {
        assertEquals(null, BarGesture.RightBar().up(5_000))
    }
}
