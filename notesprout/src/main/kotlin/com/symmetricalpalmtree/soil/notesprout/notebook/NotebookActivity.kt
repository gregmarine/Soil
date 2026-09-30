package com.symmetricalpalmtree.soil.notesprout.notebook

import android.os.Binder
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
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookAction
import com.symmetricalpalmtree.soil.notesprout.data.NotebookDocument
import com.symmetricalpalmtree.soil.notesprout.data.NotebookPrefs
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore
import com.symmetricalpalmtree.soil.notesprout.databinding.ActivityNotebookBinding
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
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **The notebook.** A full screen of paper, opened by Soil with a notebook's id, or with the name
 * of a notebook to make. The skeleton is `:paper`'s [InkScreenActivity]; what is here is the
 * notebook's own: its pages and their persistence through the seam, the session's park and
 * resume, and its place as the app in front.
 *
 * **The rows live in Soil.** The notebook is held open through an [ISeamItem]; the screen parks
 * it when it leaves the front and takes it up again when it returns, so a notebook left in the
 * background holds no file. The device has no Back key: the top bar's close button is the way
 * out, and it closes the notebook in Soil.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The exceptions are the pad's.
 */
class NotebookActivity : InkScreenActivity<NotebookAction>() {

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var toolbar: NotebookToolbar
    private lateinit var prefs: NotebookPrefs
    private var document: NotebookDocument? = null

    /** Any binder of this app's own: Soil watches it, and closes the notebook if the app dies. */
    private val owner = Binder()
    private var session: ISeamItem? = null
    private var itemId: String? = null

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null
    override val inkPage: InkPage? get() = document
    override val storeFailedTitleRes: Int get() = R.string.store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.store_failed_body

    override val initialChromeHidden: Boolean get() = prefs.chromeHidden
    override fun onChromeChanged(hidden: Boolean) { prefs.chromeHidden = hidden }
    override fun record(action: InkAction) = undo.record(NotebookAction.Ink(action))
    override fun syncTool(tool: Tool) = toolbar.sync(tool)
    override fun armTool(tool: Tool) = toolbar.arm(tool)
    override fun showPage() = showPage(firstLoad = false)
    override suspend fun revert(action: NotebookAction) { document?.revert(action) }
    override suspend fun reapply(action: NotebookAction) { document?.reapply(action) }

    // ── Create ──────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = NotebookPrefs(this)
        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.directInk = true
        paper.setPaperListener(paperListener)

        toolbar = NotebookToolbar(
            paper = paper,
            onSynced = { syncCollapsed() },
            bottomBar = binding.bottomBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            title = binding.title,
            pageIndicator = binding.pageIndicator,
            penLevel = prefs.penLevel,
            onBack = { exit() },
            onPrevPage = { runPageOp { flipTo(pageIndex() - 1) } },
            onNextPage = { runPageOp { flipTo(pageIndex() + 1) } },
            onEraserReTap = { toggleEraserBar() },
            onToolTapped = { hideEraserBar() },
        )
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
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        lifecycleScope.launch { openNotebook() }
    }

    /**
     * One notebook screen at a time. Asked for the notebook already showing, it stays; asked for
     * another, or a new one, it lets this one go, flushed and closed, and starts again with the
     * new ask. What was undoable in the old notebook goes with it.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        if (newName.isNullOrBlank() && askedId != null && askedId == itemId) return
        setIntent(intent)
        if (closing) return
        closing = true
        val page = inkPage
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { page?.flushUntilClean() }.onFailure { Log.w(TAG, "flush failed: ${it.javaClass.simpleName}") } }
            }
            if (!isFinishing && !isDestroyed) {
                paper.releaseForHandoff()
                recreate()
            }
        }
    }

    // ── Open ──────

    private suspend fun openNotebook() {
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (newName.isNullOrBlank() && askedId.isNullOrEmpty()) {
            refuse(R.string.open_missing)
            return
        }
        val soil = (application as NotesproutApp).soil
        val (w, h) = surfaceSize()
        val doc = try {
            withContext(Dispatchers.IO) {
                val seam = soil.seam()
                val item = if (!newName.isNullOrBlank()) seam.createItem(newName, NotebookSchema.SCHEMA)
                else seam.item(askedId!!) ?: throw IllegalStateException(NO_SUCH_ITEM)
                val opened = seam.openItem(item.id, NotebookSchema.SCHEMA, owner)
                session = opened
                itemId = item.id
                val store = NotebookStore(SeamRowStore(opened), item.id)
                val loaded = if (!newName.isNullOrBlank()) {
                    store.initialize(item.name, w, h).also { seam.setPageCount(item.id, 1) }
                } else {
                    store.load()
                }
                val document = NotebookDocument(store) { pages ->
                    withContext(Dispatchers.IO) { runCatching { soil.seam().setPageCount(item.id, pages) } }
                }
                document.load(loaded)
                prefs.lastNotebookId = item.id
                document to item.name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the notebook could not be opened: ${e.javaClass.simpleName}")
            refuse(
                when {
                    e is SeamUnavailable -> R.string.open_no_soil
                    e.message == SeamLimits.LIBRARY_NOT_OPEN -> R.string.open_locked
                    e.message == NO_SUCH_ITEM -> R.string.open_missing
                    e.message == SeamLimits.SCHEMA_NEWER -> R.string.open_newer
                    else -> R.string.open_failed
                },
            )
            return
        }
        if (isFinishing || isDestroyed || closing) return
        document = doc.first
        toolbar.setTitle(doc.second)
        showPage(firstLoad = true)
        opened = true
        pushExclusions()
        // Not pen-idle-gated: the pen is already over the glass on its way to write. A boundary
        // frame, not a frame during writing.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "opened: ${doc.first.pageCount} pages" }
    }

    /** A notebook that did not open is explained, and then the screen leaves as every exit does. */
    private fun refuse(bodyRes: Int) {
        openingOverlay?.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.open_failed_title)
                .setMessage(bodyRes)
                .setPositiveButton(com.symmetricalpalmtree.soil.paper.R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create(),
        ).show()
    }

    // ── Page gestures → operations ──────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one: the notebook grows where you write.
            val doc = document ?: return@runPageOp
            if (doc.pageIndex < doc.pageCount - 1) flipTo(doc.pageIndex + 1) else doInsert(after = true)
        }
        override fun onFlipPrevious() = runPageOp { flipTo(pageIndex() - 1) }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onPageSheetRequested() = confirmDeletePage()
        override fun onFingerDoubleTap(x: Float, y: Float) = toggleChrome()
    }

    private fun pageIndex(): Int = document?.pageIndex ?: 0

    private suspend fun flipTo(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.pageCount) return
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

    /** The page-swap order is g-paper's law: clear for the swap, size, template, then strokes. */
    private fun showPage(firstLoad: Boolean) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()
        hideEraserBar()
        dismissCollapsed()
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(doc.pageWidth.toInt(), doc.pageHeight.toInt())
        paper.setTemplate(null)   // the paper library arrives later
        paper.loadStrokes(doc.strokes)
        toolbar.setPage(doc.pageNumber, doc.pageCount)
    }

    private fun confirmDeletePage() {
        if (!opened || closing) return
        paper.releaseRender()
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_page_title)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { doDelete() } }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        ).show()
    }

    // ── The app in front ──────

    /** Let the panel go for a frame: the side menu is about to be drawn over this screen. */
    fun letPanelGo() {
        if (opened && !closing && !paper.isPenActive) paper.releaseRender()
    }

    /** Release the pipeline: a paper screen of Soil's is about to open over this one. */
    fun letPipelineGo() {
        if (opened && !closing) paper.releaseForHandoff()
    }

    override fun onResume() {
        super.onResume()
        (application as NotesproutApp).front(this)
    }

    override fun onPause() {
        (application as NotesproutApp).left(this)
        super.onPause()
    }

    // ── Park and resume ──────

    override fun onStart() {
        super.onStart()
        val open = session ?: return
        runPageOp { withContext(Dispatchers.IO) { open.resume() } }
    }

    override fun onStop() {
        super.onStop()
        val open = session ?: return
        if (closing) return
        // After the pause flush, which holds the same lock and was queued first.
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock {
                    withContext(Dispatchers.IO) { runCatching { open.park() }.onFailure { Log.w(TAG, "park failed: ${it.javaClass.simpleName}") } }
                }
            }
        }
    }

    override fun onScreenDestroyed() {
        super.onScreenDestroyed()
        val open = session ?: return
        session = null
        appScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { open.close(true) }.onFailure { Log.w(TAG, "close failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val TAG = "NotebookActivity"
        private const val NO_SUCH_ITEM = "there is no such item"

        /** Outlives the screen, so a park or a close in flight always completes. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
