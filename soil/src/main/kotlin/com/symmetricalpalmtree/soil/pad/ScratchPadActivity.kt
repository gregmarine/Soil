package com.symmetricalpalmtree.soil.pad

import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seam.SeamClip
import com.symmetricalpalmtree.soil.data.index.ClipStore
import android.widget.Toast
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
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.SeamClients
import com.symmetricalpalmtree.soil.paper.ink.InkWire
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
import com.symmetricalpalmtree.soil.paper.chrome.LassoPopup
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.InkPlacement
import com.symmetricalpalmtree.soil.paper.ink.InkScreenActivity
import com.symmetricalpalmtree.soil.shell.MenuSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.shell.PaperFront
import com.symmetricalpalmtree.soil.shell.SoilBarService

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
 * **The clipboard is the notebook's shape exactly** (Greg, 2026-10-04 and 2026-10-06): **strokes
 * are the lasso's, pages are the page sheet's.** Copy on the selection bar puts the lasso's
 * strokes on the library's one ink clipboard as a notebook's own Copy writes it
 * ([InkClip.envelopeOf]); while the lasso is armed and ink is on the clipboard, **a stylus tap on
 * bare paper pastes it centred on the tap** (the notebook's tap-to-place), selected; the
 * [LassoPopup] under a re-tap holds Paste at the source coordinates and Clear.
 * A finger long-press raises the page sheet: Copy page (also the top bar's button) writes the
 * page as a notebook page clip ([InkClip.pageEnvelopeOf]), which a notebook pastes before or
 * after a page and the calendar lays on its own; Paste page lands a copied page — a notebook's,
 * the calendar's — as a **new page after this one**, at the copied page's size; Delete page is
 * the confirm it always was. The clipboard is stored, it outlives the pad and Soil, and what
 * becomes of it is the paster's. The pad stays where it is. Ink a notebook sends to the pad
 * lands here as the pad shows, where the notebook said, selected.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The page indicator waits for the gate
 * ([ScratchToolbar]); the frames that do not are recorded exceptions — the delete confirm at a
 * long-press, the selection bar's show at lasso completion (and its re-anchor after a move), the
 * "Opening…" box's hide when the page lands, a problem dialog at a pen-up or at a chrome tap, and
 * the chrome flip at a finger double-tap.
 */
class ScratchPadActivity : InkScreenActivity<ScratchAction>() {

    private lateinit var lassoPopup: LassoPopup

    /** What the ink clipboard holds — [ClipEnvelope.KIND_OBJECTS], [ClipEnvelope.KIND_PAGE] or
     *  null — read at every resume and set by every copy. Each Paste is offered on its own kind. */
    @Volatile
    private var clipKind: String? = null
        set(value) {
            field = value
            markClipboard(value == ClipEnvelope.KIND_OBJECTS)
        }

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
        // Started by a Sprout app over its paper. The pad lives in a task of its own, so an app
        // starts it plainly and names no caller: the manifest's seam permission, which only an app
        // signed with Soil's key can hold, is the guard. A caller that did name itself is checked.
        val launchedByApp = intent.action == Seam.ACTION_SCRATCH_PAD
        if (launchedByApp && callingPackage != null && runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isFailure) { finish(); return }
        isOpen = true
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
            btnSend = binding.btnSend,
            showSend = true,
            onSend = { runPageOp { copyPage() } },
            onBack = { exit() },
            // No-op at a bound, never disabled: a greyed control is invisible on e-ink.
            onPrevPage = { runPageOp { flipTo(pageIndex() - 1) } },
            onNextPage = { runPageOp { flipTo(pageIndex() + 1) } },
            // A second tap on the armed eraser toggles its sub-bar — Point · Lasso; arming a
            // different tool takes the bar with it.
            onEraserReTap = { hideLassoPopup(); toggleEraserBar() },
            onLassoReTap = { if (lassoPopup.isShowing) hideLassoPopup() else showLassoPopup() },
            onToolTapped = { hideFloatingBars() },
        )
        lassoPopup = LassoPopup(
            root = binding.root, bar = binding.lassoPopup, anchor = binding.btnLasso, bandBottom = { chromeBand()?.last },
            releaseRender = { paper.releaseRender() },
            onPaste = { hideLassoPopup(); runPageOp { pasteStrokes(tapX = null, tapY = null) } },
            onClear = { hideLassoPopup(); clearClipboard() },
        )
        // After the toolbar: a pick lands on `toolbar.arm` (a tool set from our side is never
        // echoed back as `onToolChanged`, so the buttons are synced by hand).
        eraserBar = EraserBar(
            root = binding.root,
            bar = binding.eraserBar,
            anchor = binding.btnEraser,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            onPicked = { hideFloatingBars(); toolbar.arm(it) },
        )
        selectionBar = InkSelectionBar(
            root = binding.root,
            paperView = paper.asView(),
            bar = binding.selectionToolbar,
            band = { chromeBand() },
            releaseRender = { paper.releaseRender() },
            deleteHint = getString(R.string.delete_selection_action),
            onDelete = { currentSelection?.let { deleteSelection(it) } },
            copyHint = getString(R.string.scratch_copy_selection),
            onCopy = { currentSelection?.strokeIds?.toHashSet()?.let { copySelection(it) } },
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
        consumeIncoming()
    }

    /** The pad is up and asked for again: a notebook may have parked ink since. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (opened && !closing) consumeIncoming()
    }

    // ── Send, and what arrives ──────

    /**
     * Ink a notebook parked for the pad, placed where the notebook said and landed selected with
     * the lasso armed, as one undo step. Taken once: a parking that cannot be placed is explained,
     * never re-applied at the next open.
     */
    private fun consumeIncoming() {
        val parked = PadTransfer.takeIncoming() ?: return
        val bundle = InkWire.decode(parked.bytes)
        if (bundle == null || bundle.strokes.isEmpty()) {
            Dialogs.problem(this, R.string.scratch_received_failed_title, R.string.scratch_received_failed_body)
            return
        }
        runPageOp {
            val doc = document ?: return@runPageOp
            val placed = runCatching { doc.receive(bundle, newPage = parked.placement == Seam.PAD_PLACEMENT_NEW_PAGE) }
                .onFailure { Log.w(TAG, "the sent ink could not be placed: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (placed == null) {
                Dialogs.problem(this, R.string.scratch_received_failed_title, R.string.scratch_received_failed_body)
                return@runPageOp
            }
            undo.record(placed)
            showPage()
            landSelected(bundle.strokes)
            Slog.d(TAG) { "received ${bundle.strokes.size} strokes (newPage=${parked.placement == Seam.PAD_PLACEMENT_NEW_PAGE})" }
        }
    }

    /** What arrived, selected with the lasso armed, so the pen can drag it into place at once. */
    private fun landSelected(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        var box = strokes.first().bounds
        for (i in 1 until strokes.size) box = box.union(strokes[i].bounds)
        toolbar.arm(Tool.LASSO)
        val ids = strokes.mapTo(HashSet()) { it.id }
        paper.setSelection(ids, emptySet(), box)
        selectionActive = true
        currentSelection = Selection(ids, emptySet(), box)
        selectionBar.show(box)
        pushExclusions()
    }

    // ── The clipboard ────────────────────────────────────────────────────────

    /**
     * The page sheet, on a finger long-press as the notebook's is (Greg, 2026-10-06): the whole
     * page's copy and paste, and the page's delete — Copy page; Paste page while the clipboard
     * holds a page; Delete page, behind its confirm. A long press asks; it never acts. Ungated
     * releaseRender() is safe here only because the long-press fired through PageGestures' own
     * gate: it never arms while the pen is active and re-checks at fire.
     */
    private fun showPageSheet() {
        if (!opened || closing || isFinishing || isDestroyed) return
        paper.releaseRender()
        val sheet = ActionSheetDialog(this)
            .title(getString(R.string.scratch_page_sheet_title))
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_copy, getString(R.string.scratch_copy_page)) { runPageOp { copyPage() } }
        // Absent, never disabled, while the clipboard holds no page.
        if (clipKind == ClipEnvelope.KIND_PAGE) {
            sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_clipboard, getString(R.string.scratch_paste_page)) { runPageOp { pastePage() } }
        }
        sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, getString(R.string.scratch_delete_page)) { confirmDeletePage() }
        sheet.show()
    }

    /** Open the clipboard popup under the armed lasso, or keep the re-tap's silent no-op with no ink to offer. */
    private fun showLassoPopup() {
        if (!opened || closing || clipKind != ClipEnvelope.KIND_OBJECTS) return
        hideFloatingBars()
        if (lassoPopup.show()) pushExclusions()
    }

    private fun hideLassoPopup() {
        if (!::lassoPopup.isInitialized || !lassoPopup.isShowing) return
        lassoPopup.hide()
        pushExclusions()
    }

    override fun hideFloatingBars() {
        super.hideFloatingBars()
        hideLassoPopup()
    }

    /** A contact spent taking the popup down is not a placement: rewritten at every pointer down. */
    private var tapDismissedPopup = false

    /** A contact outside the popup takes it down; the lasso button is excluded, or its re-tap
     *  would close the popup here and reopen it in the toolbar. */
    override fun dismissFloatingOnContact(ev: android.view.MotionEvent, index: Int) {
        val x = ev.getX(index).toInt()
        val y = ev.getY(index).toInt()
        val dismissed = ::lassoPopup.isInitialized && lassoPopup.isShowing &&
            PaperToolbar.rectOf(binding.btnLasso)?.contains(x, y) != true && !lassoPopup.contains(x, y) && !collapsedContains(x, y)
        tapDismissedPopup = dismissed
        if (dismissed) hideLassoPopup()
    }

    /** A stylus tap on bare paper under the lasso with nothing selected: paste here, centred on
     *  the tap. Silent when the clipboard holds no ink: nothing was offering a paste. */
    override fun onLassoTap(x: Float, y: Float) {
        if (tapDismissedPopup || clipKind != ClipEnvelope.KIND_OBJECTS) return
        runPageOp { pasteStrokes(tapX = x, tapY = y) }
    }

    override fun keepCollapsedUnder(x: Int, y: Int): Boolean =
        ::lassoPopup.isInitialized && lassoPopup.isShowing && lassoPopup.contains(x, y)

    override fun extraFloatingRects(): List<android.graphics.Rect> =
        super.extraFloatingRects() + (if (::lassoPopup.isInitialized) lassoPopup.rects() else emptyList())

    override fun extraFloatingContains(x: Int, y: Int): Boolean =
        super.extraFloatingContains(x, y) || (::lassoPopup.isInitialized && lassoPopup.contains(x, y))

    /** The lasso's clipboard mark, on the bar and on the collapsed chrome alike. */
    private fun markClipboard(loaded: Boolean) {
        if (::toolbar.isInitialized) toolbar.showClipboardLoaded(loaded)
        collapsedClipboardLoaded(loaded)
    }

    /**
     * Copy the lasso's strokes to the clipboard, and stay. The pad keeps its ink and nothing goes
     * on its undo stack. The page is flushed first, under the page-op lock. An empty pick, or
     * one over the clipboard's cap, is a dialog, never silence; a copy that landed says so, since
     * nothing else on the pad changes to show it.
     */
    private fun copySelection(ids: Set<String>) {
        if (!opened || closing) return
        runPageOp {
            val doc = document ?: return@runPageOp
            doc.flushUntilClean()
            val picked = doc.strokes.filter { it.id in ids }
            val now = System.currentTimeMillis()
            val envelope = InkClip.envelopeOf(picked, now)
            if (envelope == null) {
                Dialogs.problem(this, R.string.scratch_nothing_to_copy_title, R.string.scratch_nothing_to_copy_body)
                return@runPageOp
            }
            val bytes = if (InkWire.withinLimits(picked)) ClipEnvelope.encode(envelope) else null
            if (bytes == null) {
                Dialogs.problem(this, R.string.scratch_too_large_title, R.string.scratch_too_large_body)
                return@runPageOp
            }
            if (!putClip(envelope, bytes)) return@runPageOp
            Slog.d(TAG) { "copied ${envelope.rows.size} strokes to the clipboard" }
            Toast.makeText(this, R.string.scratch_copied_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** Copy page: the showing page, blank paper, as a notebook page clip. An empty page travels:
     *  a blank page is a page. */
    private suspend fun copyPage() {
        val doc = document ?: return
        doc.flushUntilClean()
        val page = doc.currentInk()
        val now = System.currentTimeMillis()
        val envelope = InkClip.pageEnvelopeOf(listOf(InkClip.PageInk(page.width, page.height, null, page.strokes)), now) { ScratchStore.newId() }
        val strokes = page.strokes.map { it.second }
        val bytes = if (envelope != null && InkWire.withinLimits(strokes)) ClipEnvelope.encode(envelope) else null
        if (envelope == null || bytes == null) {
            Dialogs.problem(this, R.string.scratch_too_large_title, R.string.scratch_too_large_body)
            return
        }
        if (!putClip(envelope, bytes)) return
        Slog.d(TAG) { "copied the page: ${strokes.size} strokes, ${bytes.size} bytes" }
        Toast.makeText(this, R.string.scratch_copied_toast, Toast.LENGTH_SHORT).show()
    }

    /** Put [envelope] on the ink clipboard. False, after a dialog, when it would not take. */
    private suspend fun putClip(envelope: ClipEnvelope, bytes: ByteArray): Boolean {
        val written = withContext(Dispatchers.IO) {
            runCatching { ClipStore().put(InkClip.SLOT, SeamClip(envelope.kind, "", envelope.copiedAt), bytes) }
                .onFailure { Log.w(TAG, "the clipboard was not written: ${it.javaClass.simpleName}") }.isSuccess
        }
        if (!written) {
            Dialogs.problem(this, R.string.scratch_copy_failed_title, R.string.scratch_copy_failed_body)
            return false
        }
        clipKind = envelope.kind
        return true
    }

    /** The clipboard's payload, or null for none or unreadable. IO. */
    private suspend fun readClip(): ClipEnvelope? = withContext(Dispatchers.IO) {
        runCatching { ClipEnvelope.decode(ClipStore().bytes(InkClip.SLOT)) }
            .onFailure { Log.w(TAG, "the clipboard was not read: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * The lasso's Paste: the clipboard's ink onto the showing page under fresh ids — centred on
     * the stylus tap, or at the source coordinates from the popup's row — selected with the lasso
     * armed so the pen can drag it on at once. Anything but an objects payload — gone, or a page
     * copied since — is refused with a dialog and the mark drops.
     */
    private suspend fun pasteStrokes(tapX: Float?, tapY: Float?) {
        val doc = document ?: return
        val env = readClip()
        if (env == null || env.kind != ClipEnvelope.KIND_OBJECTS) {
            clipKind = env?.kind
            Dialogs.problem(this, R.string.scratch_paste_failed_title, R.string.scratch_paste_failed_body)
            return
        }
        val strokes = InkClip.strokesOf(env)
        val placed = if (tapX != null && tapY != null) InkPlacement.centredOn(strokes, tapX, tapY, doc.pageWidth, doc.pageHeight) { ScratchStore.newId() }
        else InkPlacement.atSource(strokes, doc.pageWidth, doc.pageHeight) { ScratchStore.newId() }
        if (placed.isEmpty()) {
            Dialogs.problem(this, R.string.scratch_paste_failed_title, R.string.scratch_paste_empty_body)
            return
        }
        undo.record(doc.receive(InkWire.Bundle(doc.pageWidth, doc.pageHeight, placed), newPage = false))
        showPage()
        landSelected(placed)
        Slog.d(TAG) { "pasted ${placed.size} strokes" }
    }

    /**
     * The page sheet's Paste page: a copied page as a **new page after this one**, at the copied
     * page's size (the pad's own when it names none), its ink at its own coordinates under fresh
     * ids — of a Day's two pages, the first. One undo step: the page with its cargo.
     */
    private suspend fun pastePage() {
        val doc = document ?: return
        val env = readClip()
        if (env == null || env.kind != ClipEnvelope.KIND_PAGE) {
            clipKind = env?.kind
            Dialogs.problem(this, R.string.scratch_paste_page_failed_title, R.string.scratch_paste_page_failed_body)
            return
        }
        val firstPage = env.rows.firstOrNull { it.type == "page" }?.id
        val onFirst = env.rows.filter { it.parentId == firstPage }.mapTo(HashSet()) { it.id }
        val strokes = InkClip.strokesOf(env).filter { firstPage == null || it.id in onFirst }
        val (w, h) = InkClip.pageSizeOf(env)?.takeIf { it.first > 0f && it.second > 0f } ?: (doc.pageWidth to doc.pageHeight)
        val placed = InkPlacement.atSource(strokes, w, h) { ScratchStore.newId() }
        undo.record(doc.receive(InkWire.Bundle(w, h, placed), newPage = true))
        showPage()
        Slog.d(TAG) { "pasted a page: ${placed.size} strokes at ${w.toInt()} × ${h.toInt()}" }
        Toast.makeText(this, getString(R.string.scratch_pasted_page_toast, doc.pageIndex + 1), Toast.LENGTH_SHORT).show()
    }

    /** Throw the clipboard away, and the mark with it. Never throws. */
    private fun clearClipboard() {
        if (!opened || closing) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { ClipStore().clear(InkClip.SLOT) } }
            clipKind = null
            Toast.makeText(this@ScratchPadActivity, R.string.scratch_clipboard_cleared_toast, Toast.LENGTH_SHORT).show()
        }
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
        override fun onPageSheetRequested() = showPageSheet()
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

    override fun onResume() {
        super.onResume()
        // The clipboard is the library's: a notebook or the calendar may have copied to it while
        // the pad was behind. A failed read leaves what was known.
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { ClipStore().header(InkClip.SLOT)?.payloadKind } }
            if (read.isSuccess) clipKind = read.getOrNull()
        }
        // The side menu is drawn over this screen, and shows only once the panel is let go.
        // `opened` says the paper exists and has its page.
        MenuSignals.beforeMenuShows = { if (opened && !closing && !paper.isPenActive) paper.releaseRender() }
        MenuSignals.penActive = { opened && !closing && penRecentlyActive() }
        PaperFront.ownPaper(true)
    }

    override fun onPause() {
        PaperFront.ownPaper(false)
        MenuSignals.beforeMenuShows = null
        MenuSignals.penActive = null
        super.onPause()
    }

    /** The bars reach the shell from this window while the pad is in front (see [PaperFront]). */
    override fun onBarKey(event: android.view.KeyEvent) {
        SoilBarService.barKey(event.keyCode, event.action, event.eventTime, event.repeatCount)
    }

    override fun onScreenDestroyed() {
        super.onScreenDestroyed()
        if (::binding.isInitialized) isOpen = false
    }

    companion object {
        private const val TAG = "ScratchPadActivity"

        /**
         * True while a pad screen exists, shown or not. The Encryption screen asks before it
         * re-keys or locks: the pad's store cannot be taken from under a live page.
         */
        @Volatile
        var isOpen: Boolean = false
            private set
    }
}
