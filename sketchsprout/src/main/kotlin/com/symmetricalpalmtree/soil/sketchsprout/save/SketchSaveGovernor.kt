package com.symmetricalpalmtree.soil.sketchsprout.save

/**
 * The save state machine of one raster — **pure Kotlin, no Android types at all** (Notesprout SN's,
 * arc 43), so every rule that decides whether a drawing is written is pinned by a plain JUnit test
 * instead of by a device walk.
 *
 * **One of these governs one raster.** A page is a graphite image and an ink image, each its own
 * row and its own write, so [SketchSaver] holds one governor per raster and asks each separately —
 * which is what makes "a pencil scribble never re-encodes the ink" true rather than aspirational.
 *
 * The saves cross a process boundary (the seam), which makes three things true at once:
 *
 * - **A save can fail** — Soil may be gone, the session parked. A failure must leave the raster
 *   *dirty*, never quietly "saved", and its bytes parked: they have no other copy.
 * - **A save takes time.** A raster is encoded off the main thread and the hand goes on drawing
 *   underneath it. The newest image wins, and it wins **after** the one in flight finishes.
 * - **Most triggers have nothing to do.** A page turn, `onPause`, Back each ask for a save; most
 *   are of an image already on disk, and re-encoding ~9.5 MB to write the same bytes again is the
 *   most expensive way to do nothing.
 *
 * **Dirty is a flag, not a comparison.** The engine's own `onRasterChanged(layer, …)` is the truth
 * ([markDirty]), and the flag is cleared **when the copy is taken** rather than when the write
 * lands — a mark arriving during the encode re-dirties the raster and a second save follows it.
 *
 * The governor never times anything. The debounce, the pen-idle gate and the retry delay are
 * [SketchSaver]'s, because they are Android and this is not.
 */
class SketchSaveGovernor {

    /** Whether this raster on the glass holds something the file has not been given. */
    var dirty: Boolean = false
        private set

    /** True while a write is between [request]/[flushRequest] and [onSaved]/[onFailed]/[onCopyFailed]. */
    var inFlight: Boolean = false
        private set

    /** The engine reported a change to this raster (a mark, a rub batch, an undo swap, a bake). */
    fun markDirty() {
        dirty = true
    }

    /** The page was just loaded: what is on the glass is what is on disk. */
    fun markClean() {
        dirty = false
    }

    /**
     * An ordinary trigger — the debounce tick, a page turn, `onPause`, a retry. The answer says what
     * the caller should do next and nothing else; no work is started here.
     *
     * [SaveAction.Save] means **take the copy now**: `dirty` is cleared at this moment, because the
     * copy the caller is about to take is the state being cleared.
     */
    fun request(): SaveAction = when {
        // Newest wins, and it wins *after*: the in-flight write's own completion re-asks.
        inFlight -> SaveAction.Wait
        !dirty -> SaveAction.Idle
        else -> {
            dirty = false
            inFlight = true
            SaveAction.Save
        }
    }

    /**
     * A **leave** trigger: the final flush before Back, a park, an insert or a delete. It ignores
     * [inFlight] on purpose: the exclusion that matters is the write lock the caller holds, and a
     * leave flush queued behind an in-flight write lands after it, in order.
     */
    fun flushRequest(): SaveAction {
        if (!dirty) return SaveAction.Idle
        dirty = false
        inFlight = true
        return SaveAction.Save
    }

    /** The copy could not be taken (a page-sized allocation on a device short of exactly that). The
     *  raster stays dirty and the caller re-arms its retry; nothing was written. */
    fun onCopyFailed() {
        inFlight = false
        dirty = true
    }

    /** The write landed. Answers [SaveAction.Save] when a mark arrived while it was in the air —
     *  the caller starts that one now, after this one, never beside it. */
    fun onSaved(): SaveAction {
        inFlight = false
        return request()
    }

    /** The write failed. The raster is dirty again (its bytes are parked) and the caller re-arms
     *  the retry beat. */
    fun onFailed(): SaveAction {
        inFlight = false
        dirty = true
        return SaveAction.Retry
    }

    /** What a trigger should cause. */
    sealed interface SaveAction {
        /** Nothing to write. */
        data object Idle : SaveAction

        /** Take this raster's copy now and write it. */
        data object Save : SaveAction

        /** A write is in flight; this trigger is answered by that write's own completion. */
        data object Wait : SaveAction

        /** The write failed: the raster stays dirty, its bytes are parked, re-arm the retry. */
        data object Retry : SaveAction
    }
}
