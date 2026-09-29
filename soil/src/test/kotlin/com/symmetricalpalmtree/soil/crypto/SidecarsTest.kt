package com.symmetricalpalmtree.soil.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarsTest {

    @Test
    fun anAbsentOrEmptyWalMayGo() {
        assertTrue(Sidecars.removable(walExists = false, walLength = 0L))
        assertTrue(Sidecars.removable(walExists = true, walLength = 0L))
    }

    @Test
    fun aWalHoldingWritesIsNeverRemoved() {
        assertFalse(Sidecars.removable(walExists = true, walLength = 1L))
        assertFalse(Sidecars.removable(walExists = true, walLength = 4_152L))
    }
}
