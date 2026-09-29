package com.symmetricalpalmtree.soil.bootstrap

import com.symmetricalpalmtree.soil.bootstrap.KeyGate.Route
import com.symmetricalpalmtree.soil.data.index.SoilIndex.State
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyGateTest {

    @Test
    fun anOpenLibraryWithASavedKeyOpens() {
        assertEquals(Route.OPEN, KeyGate.route(State.READY, acknowledged = true, rotating = false))
    }

    @Test
    fun nothingOpensUntilTheKeyIsSaved() {
        assertEquals(Route.RECOVERY_KEY, KeyGate.route(State.READY, acknowledged = false, rotating = false))
    }

    @Test
    fun anInterruptedRotationIsFinishedFirst() {
        assertEquals(Route.RESUME_ROTATION, KeyGate.route(State.READY, acknowledged = true, rotating = true))
    }

    /** A commit that died between clearing the acknowledgement and clearing the marker: the key
     *  to show IS the marker's, so it is shown first. */
    @Test
    fun theKeyIsShownBeforeTheRotationIsResumed() {
        assertEquals(Route.RECOVERY_KEY, KeyGate.route(State.READY, acknowledged = false, rotating = true))
    }

    @Test
    fun aLockedLibraryAsksForTheKey_whateverTheFlagsSay() {
        for (ack in listOf(true, false)) for (rot in listOf(true, false)) {
            assertEquals(Route.UNLOCK, KeyGate.route(State.NEEDS_UNLOCK, ack, rot))
        }
    }

    @Test
    fun aLibraryStillBeingOpenedWaits() {
        assertEquals(Route.PREPARING, KeyGate.route(State.PREPARING, acknowledged = true, rotating = false))
    }

    @Test
    fun aFileNoKeyCanOpenIsBlocked_neverUnlocked() {
        for (state in listOf(State.FOREIGN_FILE, State.DAMAGED_FILE, State.UNAVAILABLE)) {
            assertEquals(Route.BLOCKED, KeyGate.route(state, acknowledged = true, rotating = false))
        }
    }
}
