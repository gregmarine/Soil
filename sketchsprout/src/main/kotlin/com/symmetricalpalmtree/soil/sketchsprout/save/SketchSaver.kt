package com.symmetricalpalmtree.soil.sketchsprout.save

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The plumbing half of a sketch page's saves (Notesprout SN's arcs 43–50, without the chunk
 * stream: Soil's seam carries a raster whole): timers, threads and the write. Every *decision* is
 * [SketchSaveGovernor]'s; this class owns only the parts that need Android.
 *
 * **A page is two rasters and they are saved apart**, one governor each, walked graphite first
 * ([RasterRows.LAYERS]): a pencil scribble never re-encodes the ink raster.
 *
 * - **The copy is taken on Main, at the moment of the trigger.** `getPageRaster(layer)` is a
 *   main-thread copy of the engine's page image, and the engine only touches those images on
 *   Main, so a copy never catches a half-written composite.
 * - **A page turn awaits the copy and nothing else** ([flushForTurn]). The encode is the expensive
 *   half — 0.5–3.5 s on a real pencil page — and a turn that awaited it made a flip straight after
 *   drawing take seconds. The copy freezes the pixels; the encode and the write go on in the
 *   background while the next page loads. The leave flushes ([flushForExit], [flushAndAwait])
 *   still await the write itself.
 * - **The encode and the write run on IO, one at a time** — *one* [Mutex] for both rasters, FIFO,
 *   so the two rasters of a page land one after the other and a save is always ordered behind
 *   the write before it.
 * - **The bookkeeping runs back on Main**, so each governor's flags are read and written from one
 *   thread only.
 * - **A failure never advances anything.** The bytes are parked under their page and layer, the
 *   retry is re-armed, that raster stays dirty. Only counts, durations and exception class names
 *   are logged — never a pixel.
 * - **A write is recorded under the page key its copy was taken on** ([PushTracker]), so a later
 *   read of that page can wait for it.
 *
 * **The debounce goes through the pen-idle gate, up to a deadline** ([SketchSaveCadence]).
 *
 * The coroutine scope is deliberately **not** cancelled when the screen goes: a save armed by
 * `onPause` must land even though the Activity is on its way out.
 */
class SketchSaver(
    /** Copies one raster off the surface — `paper.getPageRaster(layer)`. **Main thread only**; null
     *  is a blank raster, which saves as an empty array (the store's word for "no such raster"). */
    private val copyPage: (RasterLayer) -> Bitmap?,
    /** Suspends until the pen is off the glass — `paper.awaitPenIdle()`. **Main thread only.** */
    private val awaitPenIdle: suspend () -> Unit,
    /** The write itself: these bytes as that page's picture of that layer, over the seam. Blocking,
     *  called on IO under the one write lock; throws on failure. */
    private val write: (pageKey: String, layer: RasterLayer, bytes: ByteArray) -> Unit,
    /** Whether a write's failure is the store's **over the cap** — a raster no retry can land
     *  until the page is lightened. Its bytes are not parked (there is nothing to re-offer), the
     *  beat does not re-encode it, and the screen is told once through [onTooLarge]; the raster
     *  stays dirty, so the next mark tries again. */
    private val isTooLarge: (Throwable) -> Boolean = { false },
    /** **Main thread.** Called once per page and layer while a raster is over the cap. */
    private val onTooLarge: (RasterLayer) -> Unit = {},
) {

    /** The page-and-layer pairs the screen has been told are over the cap; cleared by a landing. */
    private val tooLargeTold = HashSet<Pair<String, RasterLayer>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** **One** lock for both rasters: a FIFO queue is what makes two rasters of a page land in
     *  order, and a save land after the write before it. */
    private val writeLock = Mutex()

    /** One governor per raster — same class, same rules, asked separately. */
    private val governors: Map<RasterLayer, SketchSaveGovernor> =
        RasterRows.LAYERS.associateWith { SketchSaveGovernor() }

    /** Which pages still have a write in the air. Main thread. */
    private val pushes = PushTracker()

    /** Bytes a write refused, by page and layer, held for the retry beat and for the exit: they
     *  have no other copy. One slot per page and layer; a later failure replaces an earlier one. */
    private val parked = HashMap<Pair<String, RasterLayer>, ByteArray>()
    private val parkLock = Any()

    /** The page every save goes to, set at each load. Null before the first one — and a save with
     *  no page is no save at all. */
    @Volatile
    var pageKey: String? = null

    /** Set once the screen is leaving: the timers stay down, no new debounce may arm, and no
     *  completing write may start another one (which is what makes [PushTracker.awaitAll]
     *  terminate). */
    @Volatile
    private var leaving = false

    private var debounce: Job? = null
    private var retry: Job? = null

    /** When the page first became dirty since its last copy (uptime ms), or 0 — the deadline's
     *  anchor. Main thread. */
    private var dirtySince = 0L

    /** A save past its deadline waiting for the next pen lift — completed by [notePenLifted]. */
    private var penLift: CompletableDeferred<Unit>? = null

    /** Whether **either** raster on the glass holds something the file has not been given. */
    val isDirty: Boolean get() = governors.values.any { it.dirty }

    /** Whether anything sits parked — a write that failed and has not yet been retried. */
    val hasParked: Boolean get() = synchronized(parkLock) { parked.isNotEmpty() }

    // ── What the screen says ──────

    /** The engine reported a change to one of the page's rasters. */
    fun markDirty(layer: RasterLayer) = governor(layer).markDirty()

    /** A page was just loaded: what is on the glass is what is on disk — **both** rasters. */
    fun markClean() {
        for (g in governors.values) g.markClean()
        dirtySince = 0L
    }

    /** The pen left the paper — a save past its deadline copies here, between strokes. */
    fun notePenLifted() {
        penLift?.complete(Unit)
    }

    /**
     * Restart the idle timer; a burst of marks coalesces into one write — **up to the deadline**
     * ([SketchSaveCadence]): before it, the debounce and the pen-idle gate; past it, the gate is
     * bounded — the pen going idle, else the next pen lift, else the copy regardless.
     */
    fun schedule() {
        if (leaving) return
        val now = SystemClock.uptimeMillis()
        if (dirtySince == 0L) dirtySince = now
        val since = dirtySince
        debounce?.cancel()
        debounce = scope.launch(Dispatchers.Main) {
            delay(SketchSaveCadence.debounceWait(since, now))
            if (!SketchSaveCadence.pastDeadline(since, SystemClock.uptimeMillis())) {
                awaitPenIdle()
            } else {
                val idle = withTimeoutOrNull(SketchSaveCadence.IDLE_LIMIT_MS) { awaitPenIdle() }
                if (idle == null) {
                    val lift = CompletableDeferred<Unit>().also { penLift = it }
                    val lifted = withTimeoutOrNull(SketchSaveCadence.LIFT_LIMIT_MS) { lift.await() }
                    penLift = null
                    Slog.d(TAG) { "save past its deadline: ${if (lifted == null) "copying under the pen" else "copying at the pen lift"}" }
                }
            }
            saveNow()
        }
    }

    /** Drop both timers — the screen is gone, or a save is happening right now instead. */
    fun cancelTimers() {
        debounce?.cancel(); debounce = null
        retry?.cancel(); retry = null
        penLift = null
    }

    /**
     * A save trigger: the debounce, `onPause`, a retry, a resume. Re-offers anything parked, then
     * asks **each** governor what to do with its own raster and starts a write for every one that
     * answers `Save`; the rasters nothing touched cost nothing. **Main thread only.**
     */
    fun saveNow() {
        if (leaving) return
        cancelTimers()
        dirtySince = 0L
        retryParked()
        val key = pageKey ?: return
        for (layer in RasterRows.LAYERS) {
            if (governor(layer).request() is SketchSaveGovernor.SaveAction.Save) startWrite(key, layer)
        }
    }

    /** **Main thread**, and only ever after [layer]'s governor has answered `Save`. */
    private fun startWrite(key: String, layer: RasterLayer) {
        val copy = takeCopy(layer) ?: run { armRetry(); return }
        launchWrite(key, layer, copy)
    }

    /** **Main thread.** Send [copy] on its way as [layer]'s image of the page [key] names, and record
     *  the job under **that** key so a later read of that page can wait for it. */
    private fun launchWrite(key: String, layer: RasterLayer, copy: Snapshot) {
        val job = scope.launch {
            val error = writeCopy(key, layer, copy.bitmap, copy.copyMs)
            withContext(Dispatchers.Main) { finishWrite(key, layer, error) }
        }
        pushes.track(key, job)
    }

    /**
     * **Main thread.** One write's bookkeeping. The governors are about **the glass**, not about a
     * page: by the time a background write lands the screen may be on the next page, and a mark
     * that arrived during the encode belongs to whatever page is showing now, so the follow-up
     * save goes to [pageKey]. While leaving, a follow-up is not started but not dropped either:
     * the flag goes straight back, so the leave flush's own `flushRequest` still finds it.
     */
    private fun finishWrite(key: String, layer: RasterLayer, error: Throwable?) {
        if (error != null) {
            governor(layer).onFailed()
            if (isTooLarge(error)) {
                if (tooLargeTold.add(key to layer)) onTooLarge(layer)
            } else {
                armRetry()
            }
            return
        }
        tooLargeTold.remove(key to layer)
        if (governor(layer).onSaved() !is SketchSaveGovernor.SaveAction.Save) return
        // A mark arrived during the write. It goes through the debounce and the deadline like any
        // other rather than chaining at the encoder's own rate (SN: fifteen encodes in one minute).
        governor(layer).onCopyFailed()
        if (!leaving && pageKey != null) schedule()
    }

    /**
     * The leave flush — Back, the close. Awaits everything in flight, whatever page it was for, and
     * then writes whichever rasters are dirty. Returns **true** when nothing is owed on either
     * raster and nothing is parked; a false is a drawing with no other copy, and the screen says
     * so with a two-button dialog rather than leaving quietly.
     */
    suspend fun flushForExit(): Boolean {
        leaving = true
        pushes.awaitAll()
        val clean = flushAndAwait()
        return clean && !hasParked
    }

    /**
     * The same flush **without** declaring the screen gone — what a park, an insert and a delete
     * take before they leave a page behind: the row is about to be read or the session parked, so
     * this page's pixels have to be in the file first; but the screen is staying.
     */
    suspend fun flushAndAwait(): Boolean {
        cancelTimers()
        val key = pageKey ?: return !hasParked
        // A turn may have left this page's own write in the air. Waiting here is what keeps "when
        // this returns, this page is on disk" true; it is a no-op on every other path.
        pushes.await(key)
        var clean = retryParkedAndAwait()
        for (layer in RasterRows.LAYERS) {
            var owed = false
            val copy = withContext(Dispatchers.Main + NonCancellable) {
                if (governor(layer).flushRequest() !is SketchSaveGovernor.SaveAction.Save) return@withContext null
                takeCopy(layer).also { if (it == null) owed = true }
            }
            if (copy == null) {
                if (owed) clean = false
                continue
            }
            val error = withContext(NonCancellable) { writeCopy(key, layer, copy.bitmap, copy.copyMs) }
            withContext(Dispatchers.Main + NonCancellable) {
                if (error == null) governor(layer).onSaved() else governor(layer).onFailed()
            }
            if (error != null) clean = false
        }
        return clean
    }

    /**
     * **The page turn's flush**: it awaits **only the main-thread pixel copy** of each owed raster
     * and lets the encode and the write run on IO under the one lock while the next page loads.
     * What makes that safe is [awaitPushes]: every read of a page's raster waits for that page's
     * own writes first. Returns true when nothing was owed that could not be *copied*.
     */
    suspend fun flushForTurn(): Boolean {
        cancelTimers()
        val key = pageKey ?: return true
        var copied = true
        withContext(Dispatchers.Main + NonCancellable) {
            for (layer in RasterRows.LAYERS) {
                if (governor(layer).flushRequest() !is SketchSaveGovernor.SaveAction.Save) continue
                val copy = takeCopy(layer)
                if (copy == null) {
                    copied = false
                    armRetry()
                    continue
                }
                launchWrite(key, layer, copy)
            }
        }
        return copied
    }

    /** Suspend until every background write for the page [key] names has finished. A page with
     *  nothing in the air returns at once. Called before a page's rasters are read back. */
    suspend fun awaitPushes(key: String) = pushes.await(key)

    /** Whether the page [key] names still has a write in the air. */
    fun isPushPending(key: String): Boolean = pushes.isPending(key)

    /** Re-offer **everything** parked, each under its own page and raster, in the background — a
     *  resume after a park, the retry beat. **Main thread.** A write that fails again re-parks. */
    fun retryParked() {
        val owed = synchronized(parkLock) { parked.entries.map { it.key to it.value }.also { parked.clear() } }
        for ((slot, bytes) in owed) {
            val (key, layer) = slot
            val job = scope.launch { writeBytes(key, layer, bytes) }
            pushes.track(key, job)
        }
    }

    /** [retryParked], awaited: true when every parked write landed. */
    private suspend fun retryParkedAndAwait(): Boolean {
        val owed = synchronized(parkLock) { parked.entries.map { it.key to it.value }.also { parked.clear() } }
        var clean = true
        for ((slot, bytes) in owed) {
            val (key, layer) = slot
            if (withContext(NonCancellable) { writeBytes(key, layer, bytes) } != null) clean = false
        }
        return clean
    }

    // ── The write ──────

    /** The copy, taken on Main, and how long taking it cost. */
    private class Snapshot(val bitmap: Bitmap?, val copyMs: Long)

    /** **Main thread.** One raster's copy, or null when it could not be taken (that raster stays
     *  dirty and the caller retries). */
    private fun takeCopy(layer: RasterLayer): Snapshot? = try {
        val t0 = SystemClock.elapsedRealtime()
        Snapshot(copyPage(layer), SystemClock.elapsedRealtime() - t0)
    } catch (t: Throwable) {
        governor(layer).onCopyFailed()
        Log.e(TAG, "the ${RasterRows.name(layer)} raster could not be copied for saving; it stays dirty and will be tried again", t)
        null
    }

    /** Encode [copy] and write it as [layer]; recycles the copy whatever happens. Returns the
     *  failure or null. **It puts itself on IO** rather than trusting its caller's dispatcher. */
    private suspend fun writeCopy(key: String, layer: RasterLayer, copy: Bitmap?, copyMs: Long): Throwable? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val bytes = try {
            RasterImage.encode(copy)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "the ${RasterRows.name(layer)} raster could not be encoded; it stays dirty", t)
            return@withContext t
        } finally {
            copy?.recycle()
        }
        val encoded = SystemClock.elapsedRealtime() - t0
        val error = writeBytes(key, layer, bytes)
        Slog.d(TAG) {
            "save (${RasterRows.name(layer)}): ${bytes.size} B — copy $copyMs ms, encode $encoded ms, write " +
                "${SystemClock.elapsedRealtime() - t0 - encoded} ms${if (error == null) "" else " — FAILED"}"
        }
        error
    }

    /** Write [bytes] as [layer] under the lock, parking them on failure and clearing that slot's
     *  park on success. Never on Main. */
    private suspend fun writeBytes(key: String, layer: RasterLayer, bytes: ByteArray): Throwable? = withContext(Dispatchers.IO) {
        try {
            writeLock.withLock { write(key, layer, bytes) }
            synchronized(parkLock) { parked.remove(key to layer) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (isTooLarge(t)) {
                Log.w(TAG, "sketch save refused on the ${RasterRows.name(layer)} raster: ${bytes.size} B is over the cap; not parked")
            } else {
                synchronized(parkLock) { parked[key to layer] = bytes }
                Log.w(TAG, "sketch save failed on the ${RasterRows.name(layer)} raster: ${t.javaClass.simpleName} (${bytes.size} B parked)")
            }
            t
        }
    }

    /** **Main thread.** Re-arm the retry beat after a failure — one beat for the whole page. */
    private fun armRetry() {
        if (leaving) return
        retry?.cancel()
        retry = scope.launch(Dispatchers.Main) {
            delay(RETRY_DELAY_MS)
            saveNow()
        }
    }

    private fun governor(layer: RasterLayer): SketchSaveGovernor =
        governors[layer] ?: error("no governor for $layer")

    companion object {
        private const val TAG = "SketchSaver"

        /** A failed write waits this long before trying again — the usual cause is a session
         *  parked under it, or Soil on its way back. */
        const val RETRY_DELAY_MS = 2_000L
    }
}
