package com.symmetricalpalmtree.soil.restore

/**
 * What a launch does about a restore killed mid-commit, pure and idempotent. The commit moves the
 * live library aside by two renames (the index with its sidecars, then the garden) and installs
 * the staged one by three (the garden, the index's sidecar, the index last). The installed index
 * is the commit marker. Three states, decided by the two index files: live present, the commit
 * finished and the aside is discarded; live absent and aside present, the swap did not complete
 * and the aside goes back, index last; neither, nothing is in flight.
 *
 * A live index with no live garden while the old garden is still aside is not a finished commit
 * (the commit installs its garden before its index): the aside is never deleted then. With no
 * aside index the live index is the old one and its garden goes back; with one, both are left for
 * a person. The plan is carried out by [run], which stops at the first action that fails, so the
 * index is never renamed back over a half-repaired garden.
 */
object RestoreRecovery {

    data class State(val liveIndex: Boolean, val asideIndex: Boolean, val liveGarden: Boolean, val asideGarden: Boolean, val asideSidecars: List<String> = emptyList())

    sealed class Action {
        object DeleteAside : Action() { override fun toString() = "DeleteAside" }
        object DeleteStaging : Action() { override fun toString() = "DeleteStaging" }
        /** The live garden is the staged one renamed in before the kill; the old one is still aside. */
        object DeleteLiveGarden : Action() { override fun toString() = "DeleteLiveGarden" }
        data class RenameBack(val name: String) : Action()
    }

    const val INDEX_NAME = RestoreManifest.INDEX_NAME
    const val GARDEN_NAME = RestoreManifest.GARDEN

    fun plan(state: State): List<Action> {
        val out = ArrayList<Action>(6)
        when {
            state.liveIndex && !state.liveGarden && state.asideGarden -> {
                if (!state.asideIndex) out += Action.RenameBack(GARDEN_NAME)
                out += Action.DeleteStaging
            }
            state.liveIndex -> { out += Action.DeleteAside; out += Action.DeleteStaging }
            state.asideIndex -> {
                if (state.liveGarden && state.asideGarden) out += Action.DeleteLiveGarden
                if (state.asideGarden) out += Action.RenameBack(GARDEN_NAME)
                for (sidecar in state.asideSidecars) out += Action.RenameBack(sidecar)
                out += Action.RenameBack(INDEX_NAME)
                out += Action.DeleteStaging
            }
            else -> out += Action.DeleteStaging
        }
        return out
    }

    /** Carry out [actions] in order through [perform], stopping at the first that fails. True when every one succeeded. */
    fun run(actions: List<Action>, perform: (Action) -> Boolean): Boolean {
        for (action in actions) if (!perform(action)) return false
        return true
    }
}
