package com.symmetricalpalmtree.soil.sketchsprout.sketch

/**
 * The page-turn arithmetic of the sketch screen, kept apart from the Activity so the rules that
 * are easy to get subtly wrong are proved on a laptop.
 *
 * **At either edge a turn stays put**: no dialog, no toast, and the arrows never disable — a
 * greyed control is invisible on e-ink, so a bound is a tap that honestly does nothing.
 *
 * The index recorded with an edit is where its page sat **then**; pages are inserted and deleted
 * from this screen, which is why the history is re-indexed when one is ([reindexAfterInsert] /
 * [reindexAfterDelete]). The undo replay walks back to an entry's page by key, bounded by the
 * recorded distance plus slack ([maxSteps]).
 */
object PageTurn {

    enum class Direction { PREV, NEXT }

    /** Where a turn from [index] of [count] in [direction] lands, or **null** at the edge. */
    fun targetIndex(index: Int, count: Int, direction: Direction): Int? {
        if (count <= 0 || index !in 0 until count) return null
        val next = if (direction == Direction.PREV) index - 1 else index + 1
        return next.takeIf { it in 0 until count }
    }

    /** Whether a turn from here would move at all. */
    fun canTurn(index: Int, count: Int, direction: Direction): Boolean = targetIndex(index, count, direction) != null

    /** Which way an undo replay has to walk to reach [editIndex] from [currentIndex]; null when
     *  it is already there. */
    fun directionTowards(currentIndex: Int, editIndex: Int): Direction? = when {
        editIndex < currentIndex -> Direction.PREV
        editIndex > currentIndex -> Direction.NEXT
        else -> null
    }

    /** How many turns the replay may take before it gives up and drops the entry: the recorded
     *  distance plus one step of slack. */
    fun maxSteps(currentIndex: Int, editIndex: Int): Int = kotlin.math.abs(editIndex - currentIndex) + 1

    /** Where an entry made on page [index] now sits after a page was inserted **at** [at]. Total:
     *  an insert takes nothing away. */
    fun reindexAfterInsert(index: Int, at: Int): Int = if (index >= at) index + 1 else index

    /** Where an entry made on page [index] now sits after the page **at** [at] was deleted. An
     *  entry on the deleted page itself is dropped by **key** at the call site; this stays total
     *  so a stale index can never quietly drop an entry that belongs to a page still there. */
    fun reindexAfterDelete(index: Int, at: Int): Int = if (index > at) index - 1 else index
}
