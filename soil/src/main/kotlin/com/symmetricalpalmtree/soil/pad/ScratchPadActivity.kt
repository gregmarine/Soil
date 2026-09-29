package com.symmetricalpalmtree.soil.pad

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.KeyGate
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.soil.paper.chrome.EraserBar
import com.symmetricalpalmtree.soil.paper.chrome.InkSelectionBar
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.InkScreenActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The Scratch Pad** — for the quick thought that has no place yet. It is part of Soil, tied to
 * nothing, and opened from the side menu over whatever app is in front; Back returns to that app.
 *
 * It is a full screen of its own, so the pen works as it does in a notebook. The whole skeleton
 * is `:paper`'s [InkScreenActivity] — full-bleed g-paper, the page-op lock, the undo/redo replay,
 * the debounced save against every leave flush, the chrome band and exclusions, the EPD handoff.
 * This class is what is the **pad's own**: its page list and pager, its inserts and its one page
 * action. The pages and their persistence are [ScratchDocument]'s, in the pad's own encrypted
 * store.
 *
 * **The key gate is the first statement**, before anything is inflated: nothing is written under
 * a key that has not been saved, and nothing can be while the library is locked. A shut gate
 * leads to the screen that opens it, and back here afterwards.
 *
 * **Sending** — ink to a notebook, words to a document, a drawing to a sketchbook — arrives with
 * the first Sprout app, which is what gives it somewhere to go.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The page indicator waits for the gate
 * ([ScratchToolbar]); the frames that do not are recorded exceptions — the delete confirm at a
 * long-press, the selection bar's show at lasso completion (and its re-anchor after a move), the
 * "Opening…" box's hide when the page lands, a problem dialog at a pen-up or at a chrome tap, and
 * the chrome flip at a finger double-tap.
 */
class ScratchPadActivity : InkScreenActivity<ScratchAction>() {

    private lateinit var binding: ActivityScratchPadBinding
    private lateinit var toolbar: ScratchToolbar
    private var document: ScratchDocument? = null

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    // The collapsed chrome — the corner tool button and its two rows.
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null
    override val inkPage: InkPage? get() = document
    override val storeFailedTitleRes: Int get() = R.string.scratch_store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.scratch_store_failed_body

    /** The pad opens as it was left. */
    override val initialChromeHidden: Boolean get() = PadPrefs.chromeHidden

    override fun onChromeChanged(hidden: Boolean) = PadPrefs.setChromeHidden(this, hidden)

    /** The pad's stack is one sealed type over both an ink edit and a page-list one. */
    override fun record(action: InkAction) = undo.record(ScratchAction.Ink(action))

    override fun syncTool(tool: Tool) = toolbar.sync(tool)

    override fun armTool(tool: Tool) = toolbar.arm(tool)

    override fun showPage() = showPage(firstLoad = false)

    override suspend fun revert(action: ScratchAction) {
        document?.revert(action)
    }

    override suspend fun reapply(action: ScratchAction) {
        document?.reapply(action)
    }

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. The gate leads wherever it must and brings
        // the person back here; this instance has nothing to show.
        if (Library.status.value.route != KeyGate.Route.OPEN) {
            Slog.d(TAG) { "the key gate is shut: ${Library.status.value.route}" }
            // The application's context, not this screen's: this screen is leaving, and a dialog
            // hung on it would leave with it.
            Screens.open(applicationContext, Screen.PAD)
            finish()
            return
        }
        binding = ActivityScratchPadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        // Both pen-gesture recognisers on, and armed BEFORE the listener attaches (the engine reads
        // them as it wires itself up).
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        // The page goes direct to the Supernote panel: the committed picture is the flatten base,
        // the live pen, the point eraser and the lasso's trail are painted by the app. Where the
        // panel refuses to open the page stays the ink daemon's with every overlay law intact, so
        // this is set unconditionally. Every panel post is cut around the exclusion rects —
        // PaperChrome's list is what keeps a segment drawn up to a bar from writing page pixels
        // over it.
        paper.directInk = true
        paper.setPaperListener(paperListener)

        toolbar = ScratchToolbar(
            paper = paper,
            onSynced = { syncCollapsed() },   // the corner button repaints with the bar
            bottomBar = binding.bottomBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            pageIndicator = binding.pageIndicator,
            onBack = { exit() },
            // No-op at a bound, never disabled: a greyed control is invisible on e-ink.
            onPrevPage = { runPageOp { flipTo(pageIndex() - 1) } },
            onNextPage = { runPageOp { flipTo(pageIndex() + 1) } },
            // A second tap on the armed eraser toggles its sub-bar — Point · Lasso; arming a
            // different tool takes the bar with it.
            onEraserReTap = { toggleEraserBar() },
            onToolTapped = { hideEraserBar() },
        )
        // After the toolbar: a pick lands on `toolbar.arm` (a tool set from our side is never
        // echoed back as `onToolChanged`, so the buttons are synced by hand).
        eraserBar = EraserBar(
            root = binding.root,
            bar = binding.eraserBar,
            anchor = binding.btnEraser,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            onPicked = { hideEraserBar(); toolbar.arm(it) },
        )
        selectionBar = InkSelectionBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            deleteHint = getString(R.string.delete_selection_action),
            onDelete = { currentSelection?.let { deleteSelection(it) } },
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            // The surface accepts no ink until the page is truly on it: a stroke committed now
            // would be dropped by the load's `loadStrokes` with nowhere to have been recorded.
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        // Chrome moved/appeared/disappeared: re-push the exclusion rects once the pass settles.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        // Both bars hide and show together on a finger double-tap, opening as they were left.
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        lifecycleScope.launch { openDocument() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openDocument() {
        val doc = try {
            // The first open of all mints the store, which derives a key: seconds, under the
            // "Opening…" box.
            val rows = withContext(Dispatchers.IO) {
                AppStores.open(this@ScratchPadActivity, SoilFiles.STORE_SCRATCHPAD, ScratchSchema.SCHEMA)
            }
            ScratchDocument(ScratchStore(rows)) { surfaceSize() }.also {
                document = it
                it.load()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never the message: an open's message can carry a path.
            Log.w(TAG, "the pad's store could not be opened: ${e.javaClass.simpleName}")
            document = null
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        doc.adoptSurfaceSize()
        showPage(firstLoad = true)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // The page is on the paper — take the box down. Deliberately NOT pen-idle-gated:
        // `isPenActive` counts hover, and the pen is already over the glass on the way to writing,
        // which would hold the box up over the page the person asked for. A boundary frame, not a
        // frame during writing — nothing has been drawn yet.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page loaded: ${doc.strokes.size} strokes, ${doc.pageCount} pages" }
    }

    // ── Page gestures → operations ───────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the pad grows where you write.
            val doc = document ?: return@runPageOp
            if (doc.pageIndex < doc.pageCount - 1) flipTo(doc.pageIndex + 1)
            else doInsert(after = true)
        }
        override fun onFlipPrevious() = runPageOp { flipTo(pageIndex() - 1) }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onPageSheetRequested() = confirmDeletePage()
        // A finger double-tap hides / shows the chrome. Nothing on the pad answers a single tap.
        override fun onFingerDoubleTap(x: Float, y: Float) = toggleChrome()
    }

    private fun pageIndex(): Int = document?.pageIndex ?: 0

    private suspend fun flipTo(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.pageCount) return   // no-op at a bound
        doc.goToIndex(index)
        showPage()
    }

    private suspend fun doInsert(after: Boolean) {
        val doc = document ?: return
        undo.record(doc.insert(after))
        showPage()
    }

    private suspend fun doDelete() {
        val doc = document ?: return
        undo.record(doc.deleteCurrent())
        showPage()
    }

    /**
     * Put the document's current page on the paper. The order is g-paper's page-swap law:
     * `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize` /
     * `setTemplate` → `loadStrokes`, which is a single EPD refresh. Any selection goes first,
     * because a data-in call would dismiss it anyway and it belongs to the page being left.
     */
    private fun showPage(firstLoad: Boolean) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()   // idempotent — clearSelection fires onSelectionDismissed too
        hideEraserBar()       // a floating bar never survives a content swap
        dismissCollapsed()    // and neither do the corner button's rows
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(doc.pageWidth.toInt(), doc.pageHeight.toInt())
        paper.setTemplate(null)   // the pad is plain paper: no templates, ever
        paper.loadStrokes(doc.strokes)
        toolbar.setPage(doc.pageNumber, doc.pageCount)
    }

    /**
     * Long-press asks; it never acts. One question rather than a one-row sheet — the pad has a
     * single page action. Undo puts the page **and its ink** back.
     *
     * The last page is emptied, never removed ([ScratchPages.delete]) — the pad always has a page.
     */
    private fun confirmDeletePage() {
        if (!opened || closing) return
        // Ungated releaseRender() is safe here only because the long-press fired through
        // PageGestures' own gate: it never arms while the pen is active and re-checks at fire.
        paper.releaseRender()
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_page_title)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { doDelete() } }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        ).show()
    }

    private companion object {
        const val TAG = "ScratchPadActivity"
    }
}
