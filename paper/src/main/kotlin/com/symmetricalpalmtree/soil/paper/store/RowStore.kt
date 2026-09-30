package com.symmetricalpalmtree.soil.paper.store

/**
 * The storage under an ink-on-rows screen: parameterized SQL in, rows out.
 *
 * It is an interface so `:paper` never learns where the rows live. In Soil the implementation is
 * an encrypted database in the same process; a Sprout app's will reach Soil through the seam.
 *
 * **Blocking.** Every call runs on `Dispatchers.IO`, never Main.
 */
interface RowStore {

    /**
     * Run [statements] in order, in **one transaction**: all of them land or none do. Answers the
     * rows each statement changed.
     */
    fun exec(statements: List<Statement>): LongArray

    /** Run one SELECT to completion. */
    fun query(statement: Statement): StoreRows
}
