package com.symmetricalpalmtree.soil.restore

import com.symmetricalpalmtree.soil.restore.RestoreRecovery.Action
import com.symmetricalpalmtree.soil.restore.RestoreRecovery.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 / R5: the per-item, idempotent launch-time repair, over every live/aside combination the
 * commit's five renames can leave. The two index files decide; the Gardens and sidecars follow.
 */
class RestoreRecoveryTest {

    private val index = RestoreRecovery.INDEX_NAME
    private val garden = RestoreRecovery.GARDEN_NAME
    private val wal = "$index-wal"
    private val shm = "$index-shm"

    // ── Nothing in flight ────────────────────────────────────────────────────

    @Test
    fun `ordinary launch - live index, no aside - deletes both dirs and nothing else`() {
        val plan = RestoreRecovery.plan(State(liveIndex = true, asideIndex = false, liveGarden = true, asideGarden = false))
        assertEquals(listOf(Action.DeleteAside, Action.DeleteStaging), plan)
    }

    @Test
    fun `fresh install - neither index - touches only staging`() {
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = false, liveGarden = false, asideGarden = false))
        assertEquals(listOf(Action.DeleteStaging), plan)
    }

    @Test
    fun `neither index but a stray aside Garden - left for a person, never deleted`() {
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = false, liveGarden = true, asideGarden = true))
        assertEquals(listOf(Action.DeleteStaging), plan)
        assertFalse(Action.DeleteAside in plan)
        assertFalse(Action.DeleteLiveGarden in plan)
    }

    // ── Commit finished ──────────────────────────────────────────────────────

    @Test
    fun `killed after 8e - live index present, aside whole - aside is the discarded library`() {
        val plan = RestoreRecovery.plan(State(liveIndex = true, asideIndex = true, liveGarden = true, asideGarden = true, asideSidecars = listOf(wal)))
        assertEquals(listOf(Action.DeleteAside, Action.DeleteStaging), plan)
        assertFalse(plan.any { it is Action.RenameBack })
    }

    // ── Swap did not complete ────────────────────────────────────────────────

    @Test
    fun `killed after 8a - index aside, old Garden still live - index and sidecars back, Garden untouched`() {
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = true, liveGarden = true, asideGarden = false, asideSidecars = listOf(wal, shm)))
        assertEquals(listOf(Action.RenameBack(wal), Action.RenameBack(shm), Action.RenameBack(index), Action.DeleteStaging), plan)
        assertFalse(Action.DeleteLiveGarden in plan)
    }

    @Test
    fun `killed after 8b - index and Garden aside, nothing live - both back, Garden first, index last`() {
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = true, liveGarden = false, asideGarden = true))
        assertEquals(listOf(Action.RenameBack(garden), Action.RenameBack(index), Action.DeleteStaging), plan)
    }

    @Test
    fun `killed after 8c - staged Garden live AND old Garden aside - the live one is deleted first`() {
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = true, liveGarden = true, asideGarden = true, asideSidecars = listOf(wal)))
        assertEquals(
            listOf(Action.DeleteLiveGarden, Action.RenameBack(garden), Action.RenameBack(wal), Action.RenameBack(index), Action.DeleteStaging),
            plan,
        )
    }

    @Test
    fun `killed after 8d - same as 8c, the staged index WAL beside the live name is simply overwritten by the rename back`() {
        // The staged `-wal` renamed in at 8(d) sits at the live name; the aside's own `-wal` renames
        // back over it. The plan does not need to know — RenameBack replaces.
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = true, liveGarden = true, asideGarden = true, asideSidecars = listOf(wal)))
        assertTrue(Action.RenameBack(wal) in plan)
        assertEquals(Action.RenameBack(index), plan[plan.lastIndex - 1])
    }

    @Test
    fun `fresh device restored - no old Garden ever existed - only the index renames back`() {
        val plan = RestoreRecovery.plan(State(liveIndex = false, asideIndex = true, liveGarden = true, asideGarden = false))
        assertEquals(listOf(Action.RenameBack(index), Action.DeleteStaging), plan)
    }

    // ── Invariants over the whole space ──────────────────────────────────────

    @Test
    fun `the index is always the last thing renamed back`() {
        for (state in allStates()) {
            val plan = RestoreRecovery.plan(state)
            val renames = plan.filterIsInstance<Action.RenameBack>()
            if (renames.isNotEmpty()) assertEquals(state.toString(), index, renames.last().name)
        }
    }

    @Test
    fun `DeleteLiveGarden is emitted only when both Gardens exist and the aside index does`() {
        for (state in allStates()) {
            val plan = RestoreRecovery.plan(state)
            val expected = !state.liveIndex && state.asideIndex && state.liveGarden && state.asideGarden
            assertEquals(state.toString(), expected, Action.DeleteLiveGarden in plan)
        }
    }

    @Test
    fun `DeleteAside is emitted only when the live index is present`() {
        for (state in allStates()) {
            assertEquals(state.toString(), state.liveIndex, Action.DeleteAside in RestoreRecovery.plan(state))
        }
    }

    @Test
    fun `staging is always deleted`() {
        for (state in allStates()) assertTrue(Action.DeleteStaging in RestoreRecovery.plan(state))
    }

    @Test
    fun `idempotent - the state after a repair plans to nothing but the two deletes`() {
        for (state in allStates()) {
            val after = apply(state, RestoreRecovery.plan(state))
            val again = RestoreRecovery.plan(after)
            assertTrue("$state → $after → $again", again.all { it == Action.DeleteAside || it == Action.DeleteStaging })
        }
    }

    private fun allStates(): List<State> {
        val out = ArrayList<State>()
        for (li in listOf(false, true)) for (ai in listOf(false, true)) for (lg in listOf(false, true)) for (ag in listOf(false, true))
            for (sc in listOf(emptyList(), listOf(wal), listOf(wal, shm)))
                out += State(li, ai, lg, ag, sc)
        return out
    }

    /** A model of the file system executing [plan] over [state]. */
    private fun apply(state: State, plan: List<Action>): State {
        var s = state
        for (a in plan) {
            s = when (a) {
                Action.DeleteAside -> s.copy(asideIndex = false, asideGarden = false, asideSidecars = emptyList())
                Action.DeleteStaging -> s
                Action.DeleteLiveGarden -> s.copy(liveGarden = false)
                is Action.RenameBack -> when (a.name) {
                    index -> s.copy(asideIndex = false, liveIndex = true)
                    garden -> s.copy(asideGarden = false, liveGarden = true)
                    else -> s.copy(asideSidecars = s.asideSidecars - a.name)
                }
            }
        }
        return s
    }
}
