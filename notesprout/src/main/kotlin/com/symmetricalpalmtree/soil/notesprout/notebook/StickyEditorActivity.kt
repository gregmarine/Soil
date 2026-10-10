package com.symmetricalpalmtree.soil.notesprout.notebook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookPrefs
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSql
import com.symmetricalpalmtree.soil.notesprout.databinding.ActivityStickyEditorBinding
import com.symmetricalpalmtree.soil.notesprout.objects.StickyPageRects
import com.symmetricalpalmtree.soil.notesprout.objects.toRect
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedChrome
import com.symmetricalpalmtree.soil.paper.chrome.EraserBar
import com.symmetricalpalmtree.soil.paper.chrome.InkSelectionBar
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaletteBar
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenShadeGlyph
import com.symmetricalpalmtree.soil.paper.chrome.ShadeIcon
import com.symmetricalpalmtree.soil.paper.chrome.SnapToggle
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.InkTones
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.InkScreenActivity
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp

/**
 * **A sticky note's editor**: its own paper, opened by the notebook over the notebook's own store.
 * The note's content is `stroke` rows parented to the note, in the note's space, and this screen
 * is an ink screen whose one page is that: the same op log, the same debounce, the same undo.
 *
 * The paper is the note's content size, laid top-left; what the window has beyond it is excluded
 * from ink, or the pen would write outside the note. The top bar's close saves and closes: there
 * is no cancel, every stroke is already a row.
 */
class StickyEditorActivity : InkScreenActivity<InkAction>(), NotesproutApp.FrontPaper {

    private lateinit var binding: ActivityStickyEditorBinding
    private lateinit var tools: PaperToolbar
    private lateinit var paletteBar: PaletteBar
    private lateinit var prefs: NotebookPrefs
    private lateinit var penGlyph: PenShadeGlyph
    private var showing: StickyEditorTransfer.Showing? = null
    private var document: Content? = null

    /** One page of ink over the notebook's store, the note being the parent. */
    private inner class Content(private val showing: StickyEditorTransfer.Showing, override val pageWidth: Float, override val pageHeight: Float) : InkPage {
        private val ink = InkDocument(NotebookSql, TAG)
        init { ink.reset(showing.stickyId, showing.initial) }
        override val pageId: String get() = ink.pageId
        override val strokes: List<Stroke> get() = ink.strokes
        override fun addStroke(stroke: Stroke) = ink.addStroke(stroke)
        override fun erase(ids: Collection<String>): InkAction.Erased? = ink.erase(ids)
        override fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? = ink.move(ids, dx, dy)
        override suspend fun flushUntilClean(maxPasses: Int): Boolean =
            StickyEditorTransfer.writes.withLock {
                ink.flushUntilClean(maxPasses = maxPasses) { statements ->
                    withContext(Dispatchers.IO) { try { showing.store.execAll(statements) } catch (e: StoreUnavailable) { throw e } }
                }
            }
        fun revert(a: InkAction) = ink.revert(a)
        fun reapply(a: InkAction) = ink.reapply(a)
    }

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = null
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
    // The flag is Soil's, shared by every paper screen; the local one above is the fallback.
    override suspend fun readSharedChromeHidden(): Boolean? = (application as NotesproutApp).sharedChromeHidden()
    override fun writeSharedChromeHidden(hidden: Boolean) = (application as NotesproutApp).putSharedChromeHidden(hidden)
    override fun record(action: InkAction) = undo.record(action)
    override fun syncTool(tool: Tool) = tools.sync(tool)
    override fun armTool(tool: Tool) = tools.arm(tool)
    override fun showPage() { document?.let { paper.loadStrokes(it.strokes) } }
    override suspend fun revert(action: InkAction) { document?.let { it.revert(action); it.flushUntilClean() } }
    override suspend fun reapply(action: InkAction) { document?.let { it.reapply(action); it.flushUntilClean() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = NotebookPrefs(this)
        val staged = StickyEditorTransfer.take()
        if (staged == null) {
            // Nothing staged: the process died under the editor. The rows hold what was written.
            Slog.d(TAG) { "nothing staged; leaving" }
            finish()
            return
        }
        showing = staged
        binding = ActivityStickyEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(it.asView(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.directInk = true
        paper.setPaperListener(paperListener)
        paper.tool = Tool.PEN
        paper.penWidth = NotebookToolbar.PEN_WIDTH_PX
        paper.penStyle = com.symmetricalpalmtree.gpaper.core.model.StrokeStyle.PEN
        paper.eraserRadius = NotebookToolbar.ERASER_RADIUS_PX
        penGlyph = PenShadeGlyph(binding.btnPen, InkTones.tone(prefs.penLevel))
        paper.penColor = penGlyph.ink

        tools = PaperToolbar(
            bar = binding.topBar, btnBack = binding.btnBack, btnPen = binding.btnPen, btnEraser = binding.btnEraser, btnLasso = binding.btnLasso,
            paper = paper, onBack = { exit() },
            onPenReTap = { if (paletteBar.isShowing) hidePaletteBar() else showPaletteBar() },
            onEraserReTap = { hidePaletteBar(); toggleEraserBar() },
            onToolTapped = { hideFloatingBars() },
            onSynced = { syncCollapsed() },
        )
        eraserBar = EraserBar(root = binding.root, bar = binding.eraserBar, anchor = binding.btnEraser, bandBottom = { chromeBand()?.last }, paper = paper, onPicked = { hideFloatingBars(); tools.arm(it) })
        paletteBar = PaletteBar(
            root = binding.root, bar = binding.paletteBar, anchor = binding.btnPen, bandBottom = { chromeBand()?.last }, paper = paper,
            armedLevel = { prefs.penLevel },
            onPicked = { level -> prefs.penLevel = level; applyPenShade() },
        )
        selectionBar = InkSelectionBar(
            root = binding.root, paperView = paper.asView(), bar = binding.selectionToolbar, band = { chromeBand() },
            releaseRender = { paper.releaseRender() }, deleteHint = getString(R.string.delete_selection_action),
            onDelete = { currentSelection?.let { deleteSelection(it) } },
            snap = SnapToggle(this, paper),
        )
        chrome = PaperChrome(
            paper = paper, topBar = binding.topBar, bottomStrip = null,
            extraRects = { floatingRects() }, extraContains = { x, y -> floatingContains(x, y) },
            blockAll = { !opened },
            paperRects = { offPage() },
        )
        gestures = PageGestures(host = paper.asView(), isPenActive = { paper.isPenActive }, standDown = { selectionActive }, overChrome = { chrome.overChrome(it) }, listener = gestureListener)
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()
        binding.root.post { openNote(staged) }
    }

    private fun openNote(staged: StickyEditorTransfer.Showing) {
        if (isFinishing || isDestroyed) return
        val (sw, sh) = surfaceSize()
        val w = if (staged.contentW > 0) staged.contentW.toFloat() else sw
        val h = if (staged.contentH > 0) staged.contentH.toFloat() else sh
        val doc = Content(staged, w, h)
        document = doc
        paper.setPageSize(w.toInt(), h.toInt())
        paper.setTemplate(null)
        paper.loadStrokes(doc.strokes)
        opened = true
        pushExclusions()
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "note open: ${doc.strokes.size} strokes, ${w.toInt()}x${h.toInt()}" }
    }

    /** The bands beyond the note's paper, in paper px: excluded from ink. */
    private fun offPage(): List<Rect> {
        val doc = document ?: return emptyList()
        val v = paper.asView()
        return StickyPageRects.offPage(doc.pageWidth.toInt(), doc.pageHeight.toInt(), v.width, v.height).map { it.toRect() }
    }

    private val gestureListener = object : PageGestures.Listener {
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onFingerDoubleTap(x: Float, y: Float) = toggleChrome()
    }

    private fun applyPenShade() {
        val ink = InkTones.tone(prefs.penLevel)
        paper.penColor = ink
        penGlyph.report(ink)
        syncCollapsed()
    }

    private fun showPaletteBar(anchor: View? = null) {
        if (!opened || closing) return
        hideEraserBar()
        val shown = if (anchor == null) paletteBar.show() else paletteBar.show(anchor)
        if (shown) pushExclusions()
    }

    private fun hidePaletteBar() {
        if (!::paletteBar.isInitialized || !paletteBar.isShowing) return
        paletteBar.hide()
        pushExclusions()
    }

    override fun hideFloatingBars() { super.hideFloatingBars(); hidePaletteBar() }
    override fun onCollapsedClosing() { if (::paletteBar.isInitialized) paletteBar.hide() }
    override fun keepCollapsedUnder(x: Int, y: Int): Boolean = ::paletteBar.isInitialized && paletteBar.isShowing && paletteBar.contains(x, y)
    override fun dismissFloatingOnContact(ev: android.view.MotionEvent, index: Int) {
        if (!::paletteBar.isInitialized || !paletteBar.isShowing) return
        val x = ev.getX(index).toInt()
        val y = ev.getY(index).toInt()
        if (PaperToolbar.rectOf(binding.btnPen)?.contains(x, y) == true) return
        if (paletteBar.contains(x, y) || collapsedContains(x, y)) return
        hidePaletteBar()
    }
    override fun extraFloatingRects(): List<Rect> = super.extraFloatingRects() + (if (::paletteBar.isInitialized) paletteBar.rects() else emptyList())
    override fun extraFloatingContains(x: Int, y: Int): Boolean = super.extraFloatingContains(x, y) || (::paletteBar.isInitialized && paletteBar.contains(x, y))
    override fun collapsedPenIcon(): (() -> CollapsedChrome.PenIcon) = { val ink = penGlyph.ink; CollapsedChrome.PenIcon(ink) { ShadeIcon.pen(this, ink) } }
    override fun collapsedPenReTap(): ((anchor: View) -> Unit) = { anchor -> if (paletteBar.isShowing) hidePaletteBar() else showPaletteBar(anchor) }

    /** The bars reach Soil's shell from this window while it is in front (its own filter is off over paper). */
    override fun onBarKey(event: android.view.KeyEvent) = (application as NotesproutApp).barKey(event)

    override fun onResume() {
        super.onResume()
        if (::penGlyph.isInitialized) applyPenShade()
        (application as NotesproutApp).front(this)
    }

    override fun onPause() {
        (application as NotesproutApp).left(this)
        super.onPause()
    }

    // ── What Soil asks of the paper in front ──────

    override fun penIsActive(): Boolean = opened && penRecentlyActive()
    override fun letPanelGo() { if (opened && !closing && !paper.isPenActive) paper.releaseRender() }
    override fun letPipelineGo() { if (opened && !closing) paper.releaseForHandoff() }

    companion object {
        private const val TAG = "StickyEditor"

        fun intent(context: Context): Intent = Intent(context, StickyEditorActivity::class.java)
    }
}
