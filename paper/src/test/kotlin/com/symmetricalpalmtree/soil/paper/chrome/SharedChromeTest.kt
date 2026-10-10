package com.symmetricalpalmtree.soil.paper.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedChromeTest {

    @Test fun sharedWinsOverLocalWhenPresent() {
        assertEquals(true, SharedChrome.opening(shared = true, saved = null, local = false))
        assertEquals(false, SharedChrome.opening(shared = false, saved = null, local = true))
    }

    @Test fun sharedWinsOverTheRebuiltState() {
        assertEquals(false, SharedChrome.opening(shared = false, saved = true, local = true))
    }

    @Test fun withoutSoilTheRebuiltStateThenTheLocalFlag() {
        assertEquals(true, SharedChrome.opening(shared = null, saved = true, local = false))
        assertEquals(true, SharedChrome.opening(shared = null, saved = null, local = true))
        assertEquals(false, SharedChrome.opening(shared = null, saved = null, local = false))
    }

    @Test fun adoptFlipsOnlyWhenTheSharedFlagDiffers() {
        assertEquals(true, SharedChrome.adopt(shared = true, current = false))
        assertEquals(false, SharedChrome.adopt(shared = false, current = true))
        assertNull(SharedChrome.adopt(shared = true, current = true))
        assertNull(SharedChrome.adopt(shared = false, current = false))
    }

    @Test fun noAnswerLeavesTheLocalStateStanding() {
        assertNull(SharedChrome.adopt(shared = null, current = true))
        assertNull(SharedChrome.adopt(shared = null, current = false))
    }
}
