package com.symmetricalpalmtree.soil.sketchsprout.save

import kotlinx.coroutines.Job

/**
 * Which pages still have a write in the air, keyed by the page the copy was taken on — what lets
 * a read of a page wait for its own write and for no other (Notesprout SN's, 2026-09-19).
 *
 * A page turn awaits only the main-thread copy; the encode and the write run on afterwards. So
 * a turn straight *back* to a page just drawn on must wait for that page's own writes before it
 * reads the row, and every other turn waits for nothing. **Main thread only**: the map is read
 * and written from one thread, and a job removes itself on completion through a main-posted
 * callback.
 */
class PushTracker {

    private val pending = HashMap<String, MutableSet<Job>>()

    /** Record [job] as a write for [key]; it drops out when it completes, however it completes. */
    fun track(key: String, job: Job) {
        pending.getOrPut(key) { LinkedHashSet() }.add(job)
        job.invokeOnCompletion {
            val set = pending[key] ?: return@invokeOnCompletion
            set.remove(job)
            if (set.isEmpty()) pending.remove(key)
        }
    }

    /** Whether [key] has a write in the air — a map lookup, the cheap question a load asks first. */
    fun isPending(key: String): Boolean = pending[key]?.isNotEmpty() == true

    /** Suspend until every write for [key] has finished — landed, or failed and parked. A page with
     *  nothing in the air returns at once. */
    suspend fun await(key: String) {
        while (true) {
            val job = pending[key]?.firstOrNull() ?: return
            job.join()
        }
    }

    /** Suspend until every write for every page has finished. Terminates only while nothing can
     *  start another — the saver's `leaving` flag. */
    suspend fun awaitAll() {
        while (true) {
            val job = pending.values.firstOrNull { it.isNotEmpty() }?.firstOrNull() ?: return
            job.join()
        }
    }
}
