package com.symmetricalpalmtree.soil.paper.ink

import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.chrome.InkSelectionBar
import com.symmetricalpalmtree.soil.paper.chrome.UndoRedoStack
import com.symmetricalpalmtree.soil.paper.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The skeleton of an **ink-on-rows screen**: full-bleed g-paper, two thin chrome bars,
 * [PageGestures][com.symmetricalpalmtree.soil.paper.chrome.PageGestures] /
 * [PaperChrome][com.symmetricalpalmtree.soil.paper.chrome.PaperChrome] / [UndoRedoStack] /
 * [InkSelectionBar], and a page whose strokes are rows in a
 * [RowStore][com.symmetricalpalmtree.soil.paper.store.RowStore].
 *
 * **Half of it is [PaperScreenActivity]** — everything that knows nothing about what is on the
 * paper: the chrome bars and their toggle, the collapsed corner button, the eraser sub-bar, the
 * free band, touch dispatch, the pen-idle gate, the problem dialog and the EPD handoff. What
 * stays here is what a *store-backed ink page* is:
 *
 * - the **page-op lock** and [runPageOp]: every page/undo/flush mutation is serialised, so two
 *   overlapping gestures cannot tangle the page and a debounced save can never run inside a swap;
 * - the **undo/redo replay** shape ([doUndo] / [doRedo]) with its record-clears-redo generation
 *   check, the put-the-entry-back-on-failure rule, and the [followReplay] hook;
 * - the **debounced save** ([scheduleSave], bounded — what it leaves behind the next debounce picks
 *   up) against every **leave** flush (unbounded — there is no next debounce);
 * - [onScreenPaused] / [exit] / [onScreenDestroyed], **in their exact order**;
 * - the lasso **selection bar** and everything the selection is ([deleteSelection]), which is
 *   what feeds the base's [extraFloatingRects].
 *
 * What a subclass keeps is what is *its own*: the Scratch Pad's page list and pager. It assigns
 * `paper`, `chrome`, `gestures`, [selectionBar] and `eraserBar` in its `onCreate`.
 *
 * **Back awaits the flush.** [exit] flushes under the page-op lock first and only then hands off
 * and finishes, and the pause flush runs `NonCancellable` on a scope that outlives the Activity.
 *
 * **Frame silence:** no app frame while `paper.isPenActive`. The frames that do not go through the
 * pen-idle gate are recorded exceptions — the selection bar's show at lasso completion (and its
 * re-anchor after a move), the "Opening…" box's hide when the page lands, and a problem dialog at
 * a pen-up or a chrome tap.
 *
 * [A] is the consumer's undo action type: the pad's `ScratchAction`, which wraps an [InkAction]
 * alongside its page-level one.
 */
abstract class InkScreenActivity<A : Any> : PaperScreenActivity() {

    /** The lasso's floating bar — Delete over the selection box. */
    protected lateinit var selectionBar: InkSelectionBar

    /** In-memory, screen-level history: it survives page turns and dies with the screen. */
    protected val undo = UndoRedoStack<A>()

    /** Serialises every page/undo/flush operation. */
    protected val pageOps = Mutex()

    protected var selectionActive = false
    protected var currentSelection: Selection? = null

    // ── What the consumer supplies ───────────────────────────────────────────

    /** The page being written on, or null before the document is built. */
    protected abstract val inkPage: InkPage?

    protected abstract val storeFailedTitleRes: Int
    protected abstract val storeFailedBodyRes: Int

    /** Record a stroke-level edit, wrapped in the consumer's action type. */
    protected abstract fun record(action: InkAction)

    /** Keep the consumer's toolbar honest about the armed tool. */
    protected abstract fun syncTool(tool: Tool)

    /** Put the showing page back on the paper (the host-responsibilities page-swap order). */
    protected abstract fun showPage()

    /** Reverse [action] against the consumer's document — it lands on the action's page and flushes. */
    protected abstract suspend fun revert(action: A)

    /** [revert]'s mirror. */
    protected abstract suspend fun reapply(action: A)

    /**
     * Run after a replay has landed and before the page is shown — for a screen whose navigation
     * must follow the document to the page the replay landed on. The pad has nothing to follow.
     */
    protected open fun followReplay() {}

    // ── The save debounce ────────────────────────────────────────────────────

    // The debounce is the one bounded flush — what it leaves behind, the next debounce picks up.
    // Every leave path (a page swap, onPause, exit) flushes until clean: there is no next one.
    private val saveRunnable = Runnable { runPageOp { inkPage?.flushUntilClean(maxPasses = InkDocument.MAX_FLUSH_PASSES) } }

    /** Debounced: a hand writing a line would otherwise write a statement per stroke. */
    protected fun scheduleSave() {
        val root = screenRoot ?: return
        root.removeCallbacks(saveRunnable)
        root.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    // ── g-paper → the document ───────────────────────────────────────────────

    /**
     * The listener the screen attaches. Every arm is guarded on `opened`/`closing`: the surface
     * accepts no ink until the page is truly on it, and nothing may run against the document once
     * the screen is leaving.
     */
    protected val paperListener: PaperListener = object : PaperListener {

        override fun onStrokeCommitted(stroke: Stroke) {
            lastPenLiftAt = android.os.SystemClock.uptimeMillis()
            if (!opened || closing) return
            val page = inkPage ?: return
            // A page has no ceiling (arc 22 / X2): every committed stroke is taken.
            page.addStroke(stroke)
            record(InkAction.Drew(page.pageId, stroke))
            scheduleSave()
        }

        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened || closing) return
            inkPage?.erase(strokeIds)?.let { record(it); scheduleSave() }
        }

        /**
         * The lasso eraser's one report (arc 29 / LE3, g-paper 0.1.28) — the point eraser's body:
         * these screens are ink only, so [contentIds] is always empty and is ignored, and the erase
         * records an [InkAction.Erased] like any other. The forwarding default would do the same;
         * the override is here so the intent is explicit and a content renderer added to either
         * screen later cannot silently split one gesture into two entries. No repaint — the
         * engine re-records itself.
         */
        override fun onLassoErased(strokeIds: List<String>, contentIds: List<String>) {
            if (!opened || closing) return
            inkPage?.erase(strokeIds)?.let { record(it); scheduleSave() }
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened || closing) return
            inkPage?.move(move.strokeIds, move.dx, move.dy)?.let { record(it); scheduleSave() }
            // The selection survives a move, at its new position — keep our copy honest, then bring
            // the bar back to where the box now is (the drag is over; this fires at lift).
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            currentSelection?.let { selectionBar.show(it.bounds); pushExclusions() }
        }

        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // Shown immediately, not through the pen-idle gate: a lasso ends with the pen still
            // hovering (`isPenActive` counts proximity plus a tail), so an idle-gated bar would
            // arrive long after the selection it belongs to. The engine has already presented the
            // selection box — this frame is part of that same presentation.
            selectionBar.show(selection.bounds)
            pushExclusions()
        }

        /** The pen is dragging the box — the bar would be dragged over, and it never follows live. */
        override fun onSelectionDragStarted() {
            selectionBar.hide()
            pushExclusions()
        }

        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            selectionBar.hide()
            pushExclusions()
        }

        override fun onToolChanged(tool: Tool) = syncTool(tool)

        override fun onPenLifted() { lastPenLiftAt = android.os.SystemClock.uptimeMillis() }

        override fun onPaperTapped(x: Float, y: Float) {
            if (!opened || closing) return
            onLassoTap(x, y)
        }
    }

    /**
     * A stylus tap on bare paper under the armed lasso with nothing selected — the notebook's
     * tap-to-place: a screen whose clipboard holds strokes pastes them centred here. Nothing by
     * default.
     */
    protected open fun onLassoTap(x: Float, y: Float) {}

    /** When the pen last lifted, as the engine told it. */
    @Volatile
    private var lastPenLiftAt = 0L

    /**
     * Whether the pen is active, or lifted so recently that a hand is still on the glass: what
     * Soil's menu asks before it draws over this screen. A palm resting on the side bar while
     * writing presses the bar's key for the length of a swipe, and the pen lifts between words;
     * the engine's own tail is a few hundred milliseconds, and a hand writing lifts the pen for
     * longer than that between strokes. So a recent lift counts too.
     */
    protected fun penRecentlyActive(): Boolean =
        paper.isPenActive || android.os.SystemClock.uptimeMillis() - lastPenLiftAt < PEN_RECENT_MS

    // ── Page operations ──────────────────────────────────────────────────────

    /** Serialise every page/undo/flush mutation; ignore anything while not open or once closing. */
    protected fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StoreUnavailable) {
                    Log.w(logTag, "store unavailable", e)
                    showProblem(storeFailedTitleRes, storeFailedBodyRes)
                } catch (t: Throwable) {
                    Log.w(logTag, "page op failed", t)
                }
            }
        }
    }

    protected suspend fun doUndo() {
        val a = undo.popUndo() ?: return
        val g = undo.generation
        try {
            revert(a)
        } catch (t: Throwable) {
            // Failed (or cancelled) mid-replay: put the entry back so the history never silently
            // loses a step. The store ops are idempotent, so a retry converges.
            undo.pushUndo(a)
            throw t
        }
        // A pen-up landing mid-replay recorded a fresh edit, which cleared redo — honour
        // record-clears-redo rather than re-populating redo with the entry we just undid.
        if (undo.generation == g) undo.pushRedo(a)
        followReplay()
        showPage()
    }

    protected suspend fun doRedo() {
        val a = undo.popRedo() ?: return
        try {
            reapply(a)
        } catch (t: Throwable) {
            undo.pushRedo(a)
            throw t
        }
        undo.pushUndo(a)
        followReplay()
        showPage()
    }

    protected fun deleteSelection(sel: Selection) {
        if (!opened || closing) return
        val ids = sel.strokeIds.toList()
        if (ids.isEmpty()) { paper.clearSelection(); return }
        inkPage?.erase(ids)?.let { record(it); scheduleSave() }
        // `removeStrokes` dismisses the selection itself — every data-in call does.
        paper.removeStrokes(ids)
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────

    /** A screen that opened nothing is explained, not toasted — then it leaves the way every exit does. */
    protected fun failOpen() {
        openingOverlay?.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(storeFailedTitleRes)
                .setMessage(storeFailedBodyRes)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    // ── Chrome the selection owns ────────────────────────────────────────────

    /** The lasso's bar is this screen's own floating chrome, on top of the two every paper screen
     *  has (the base composes them in that order). */
    override fun extraFloatingRects(): List<Rect> =
        if (::selectionBar.isInitialized) selectionBar.rects() else emptyList()

    override fun extraFloatingContains(x: Int, y: Int): Boolean =
        ::selectionBar.isInitialized && selectionBar.contains(x, y)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onScreenPaused() {
        if (!opened || closing) return
        val page = inkPage ?: return
        // A durability point while backgrounded, on a scope that outlives this Activity: our own
        // is cancelled at ON_DESTROY, and a half-written page is the one thing worth surviving that.
        screenRoot?.removeCallbacks(saveRunnable)
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { page.flushUntilClean() }.onFailure { Log.w(logTag, "pause flush failed", it) } }
            }
        }
    }

    /**
     * Every exit — Back, the top bar's Back, the store-failure dialog — flushes and then hands the
     * pipeline off. **The flush is awaited before `finish()`**, so nothing written is left in
     * flight behind a screen that has gone.
     */
    /** Run once the page is flushed and just before the screen finishes: a door the exit opens. */
    protected var afterExit: (() -> Unit)? = null

    protected fun exit() {
        if (closing) return
        closing = true
        hideEraserBar()   // a floating bar belongs to a screen that is leaving
        dismissCollapsed()   // and so do the corner button's rows
        screenRoot?.removeCallbacks(saveRunnable)
        val page = inkPage ?: run { afterExit?.invoke(); afterExit = null; finishWithHandoff(); return }
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { page.flushUntilClean() }.onFailure { Log.w(logTag, "final flush failed", it) } }
            }
            if (!isFinishing && !isDestroyed) {
                afterExit?.invoke()
                afterExit = null
                finishWithHandoff()
            }
        }
    }

    override fun onScreenDestroyed() {
        screenRoot?.removeCallbacks(saveRunnable)
    }

    private companion object {

        /** Quiet time before the page's op log is written. */
        const val SAVE_DEBOUNCE_MS = 800L

        /** How long after a pen lift the hand is taken to be still on the glass. */
        const val PEN_RECENT_MS = 2_000L

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
