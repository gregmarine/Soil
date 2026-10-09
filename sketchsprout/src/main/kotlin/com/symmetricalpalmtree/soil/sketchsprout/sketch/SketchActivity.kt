package com.symmetricalpalmtree.soil.sketchsprout.sketch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.gpaper.core.RasterPatch
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.ink.InkPlacement
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import com.symmetricalpalmtree.soil.sketchsprout.clip.SketchClipboard
import com.symmetricalpalmtree.soil.sketchsprout.clip.SketchPageClip
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedChrome
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PageMath
import com.symmetricalpalmtree.soil.paper.chrome.BacklinksModel
import com.symmetricalpalmtree.soil.paper.chrome.BacklinksPanel
import com.symmetricalpalmtree.soil.paper.chrome.PaletteBar
import com.symmetricalpalmtree.soil.seam.SeamBacklink
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.ShadeIcon
import com.symmetricalpalmtree.soil.paper.chrome.UndoRedoStack
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.CoverSnapshot
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.PaperScreenActivity
import com.symmetricalpalmtree.soil.paper.ink.awaitPenIdle
import com.symmetricalpalmtree.soil.paper.templates.Bitmaps
import com.symmetricalpalmtree.soil.paper.templates.PageTemplate
import com.symmetricalpalmtree.soil.paper.templates.PaperSource
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplatePick
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import com.symmetricalpalmtree.soil.sketchsprout.BuildConfig
import com.symmetricalpalmtree.soil.sketchsprout.R
import com.symmetricalpalmtree.soil.sketchsprout.SketchPrefs
import com.symmetricalpalmtree.soil.sketchsprout.SketchsproutApp
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchPage
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookStore
import com.symmetricalpalmtree.soil.sketchsprout.databinding.ActivitySketchBinding
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterEditBuilder
import com.symmetricalpalmtree.soil.sketchsprout.raster.PageFlatten
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterRows
import com.symmetricalpalmtree.soil.sketchsprout.save.SketchSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The sketchbook.** A full screen of raster paper, opened by Soil with a sketchbook's id, or with
 * the name of one to make. The base is `:paper`'s [PaperScreenActivity] — the chrome bars and
 * their toggle, the collapsed corner button, the free band, the stylus-vs-finger dispatch, the
 * pen-idle gate and **the EPD handoff** are all there, and nothing of a stroke document is: a
 * raster page has no rows of strokes. What is here is what a *raster page* is, the port of
 * Notesprout SN's sketch face onto Soil's seam.
 *
 * - **The rows live in Soil.** The sketchbook is held open through an [ISeamItem]; the screen
 *   parks it when it leaves the front and takes it up again when it returns, so a sketchbook left
 *   in the background holds no file. Its pictures are [SketchbookStore]'s rows, crossed whole.
 * - **A page is three rasters, one picture**: a **graphite** image the pencil bakes into and the
 *   rubber rubs and the smudge moves, an **ink** image the gel pen bakes into and nothing erases,
 *   and a **marker** image (2026-10-08) the marker bakes into at its own translucency, over both,
 *   off by undo alone. None is a user-facing layer: the artist sees the ink flattened over the
 *   graphite and the marker over both. Routing is g-paper's, by stroke style; this screen only
 *   ever *follows* the layer the engine names.
 * - **The tools are chosen and remembered**: a pencil, a gel pen and a marker, each with sixteen
 *   shades and a ladder of sizes, the rubber, and the smudge on the nib or under a finger's
 *   back-and-forth ([SmudgeRub]). The three pens are `Tool.PEN` to the engine, so which is armed
 *   lives in [SketchToolState]; a kind's shade and size are picked in the [PaletteBar] hung under
 *   its own button on a re-tap, and each pen button reports its shade as a fill in its glyph. The choice is
 *   device state ([SketchPrefs]), never page state; the rubber and the smudge are never
 *   remembered (Greg, 2026-10-07).
 * - **A mark is not an object.** `pageMode = RASTER` is set once, before any content: the engine
 *   composites each mark into its style's page image at pen-up and drops the stroke, so
 *   `onStrokeCommitted` is **deliberately ignored**.
 * - **Saves are [SketchSaver]'s**: debounced through the pen-idle gate up to a deadline, one
 *   raster at a time, each raster its own row; every leave flushes; a flush that fails is a
 *   dialog, because the pixels have no other copy.
 * - **Undo is pixels — and pages.** The before-image of everything one contact changed is read on
 *   a 64 px grid ([RasterEditBuilder]) between `onRasterWillChange` and `onPenLifted`, and taken
 *   back with `swapPageRaster(layer, …)`, which leaves the arrays holding the other side — so one
 *   entry is its own redo. The history is bounded by bytes ([SketchEdit.UNDO_BUDGET_BYTES]). A
 *   page insert or delete is an entry too ([SketchEdit.PagesChanged]), replayed by one reconcile
 *   of the file. **Undo and redo are gestures only** (Greg, 2026-10-07): the two- and three-finger
 *   double-taps every Soil paper screen has.
 * - **The screen turns, inserts and deletes its own pages.** Arrows and the one-finger swipe turn;
 *   a swipe past the last page makes one, a two-finger swipe makes one either side; Delete page
 *   is on the long-press sheet behind a confirm. A turn flushes the page it leaves by copy alone
 *   and lets the encode run on; an insert, a delete and a page replay await the write.
 * - **The paper is the sheet.** A page's template row (the paper library's, reused by bytes as
 *   the notebook reuses them) is decoded and handed to g-paper as the **sheet** — never as the
 *   template, which the Supernote's direct raster path does not flatten onto the panel. The sheet
 *   is drawn over white and under both rasters on the window and the panel alike, and is never
 *   in the engine's own render, so the cover and the export compose the page themselves
 *   ([PageFlatten]). The guides (phase 5) join the paper in the same one sheet.
 * - **The smudge is a gesture on top of being a tool**: a one-finger rub under any tool goes
 *   straight into the engine's smudge sweep, fed from [dispatchTouchEvent] before the base feeds
 *   the page gestures, so those see it standing down on the same event that armed it.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The title and the indicator wait for the
 * gate ([SketchToolbar]); the frames that do not are the recorded exceptions — the "Opening…" box's
 * hide when the page lands, a problem dialog at a deliberate tap, and the chrome flip at a finger
 * double-tap.
 */
class SketchActivity : PaperScreenActivity(), SketchsproutApp.FrontPaper {

    private lateinit var binding: ActivitySketchBinding
    private lateinit var toolbar: SketchToolbar
    private lateinit var saver: SketchSaver
    private lateinit var prefs: SketchPrefs

    /** The paper under the pages shown lately, by template row id: decoded off Main before the
     *  load that sets it. The engine holds the sheet by reference, so a cached bitmap stays alive
     *  until it leaves the cache after the sheet has moved on. */
    private val paperCache = LinkedHashMap<String, Bitmap>()

    /** The sheet the engine holds now, by reference; null for white. */
    private var currentSheet: Bitmap? = null

    /** Soil's template picker, started for a result. Registered as a property: a launcher must
     *  be registered before the Activity is STARTED. */
    private val templatePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onTemplatePicked(it.resultCode, it.data?.getStringExtra(Seam.EXTRA_PICK))
    }

    /** Run once the page is flushed and just before the screen finishes: a door the exit opens
     *  (Soil's export screen, which needs the file free). */
    private var afterExit: (() -> Unit)? = null

    /** Soil's screen is up over this one in another process: the pipeline is handed over as for
     *  any Soil screen, and the session is not parked. */
    private var soilScreenShowing = false
    private var inAppHandoff = false

    /** The shade panel — Atelier's sixteen tones, hung under whichever pen button was re-tapped
     *  (top bar or mini row). Null until `onCreate` builds it. */
    private var paletteBar: PaletteBar? = null

    /** The guides, and the sheet under the page with them: the grid, the reference image, their
     *  panel and their picker. Null until `onCreate` builds it. */
    private var guides: SketchGuides? = null

    /** The finger rub that smudges graphite. Coordinates are converted to the paper's at the down. */
    private val smudge = SmudgeRub(
        hopPx = SMUDGE_HOP_PX,
        armWithinPx = SMUDGE_ARM_WITHIN_PX,
        gate = { !paper.isPenActive && opened && !closing },
        listener = object : SmudgeRub.Listener {
            override fun onArmed(points: List<StrokePoint>) {
                Slog.d(TAG) { "smudge armed: ${points.size} samples so far" }
                paper.beginSmudge()
                paper.smudgeAlong(points)
            }
            override fun onRub(points: List<StrokePoint>) = paper.smudgeAlong(points)
            override fun onEnded() {
                Slog.d(TAG) { "smudge ended" }
                paper.endSmudge()
            }
        },
    )
    private val smudgeOrigin = IntArray(2)

    /** Serialises every page / flush operation. */
    private val pageOps = Mutex()

    /** The open file in Soil, until the screen closes it. */
    private var session: ISeamItem? = null
    private var store: SketchbookStore? = null
    private var itemId: String? = null

    /** Soil's hold on the session: the file is closed when this binder dies with the process. */
    private val owner = Binder()

    /** The pages as the store lists them, and the one on the glass. Null before the first load. */
    private var pages: List<SketchPage> = emptyList()
    private var currentPage: SketchPage? = null

    /** This sitting's history — pixels, so it is bounded by bytes as well as by count. It survives
     *  a page turn (each entry carries the page it happened on) and dies with the screen. */
    private val undo = UndoRedoStack<SketchEdit>(cost = { it.bytes }, budgetBytes = SketchEdit.UNDO_BUDGET_BYTES)

    /** The open contact's before-image, from its first change to the pen lifting. */
    private var openEdit: RasterEditBuilder? = null
    private var openEditReadNanos = 0L

    /** When the pen last lifted, as the engine told it — a hand writing lifts the pen between
     *  strokes for longer than the engine's own tail, so a recent lift counts as active too. */
    @Volatile
    private var lastPenLiftAt = 0L

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null

    override fun armTool(tool: Tool) = toolbar.arm(tool)

    /** Three tools on the mini toolbar: the pen (three kinds, [collapsedPenKinds]), the rubber and
     *  the stylus smudge — the row reads Pencil · Pen · Marker · Eraser · Smudge, the top bar's own
     *  order. */
    override fun collapsedTools(): List<Tool> = listOf(Tool.PEN, Tool.ERASER, Tool.SMUDGE)

    /**
     * The pen's three kinds on the mini toolbar: the graphite pencil, the gel pen and the marker,
     * all `Tool.PEN`, in [SketchToolState.Kind]'s order. A pick of the already-armed kind opens
     * the [PaletteBar] **under that row's own button** for that kind and leaves the row up beneath
     * it: the top bar's pen buttons are `GONE` while the chrome is collapsed, so a bar hung under
     * one would land under nothing. The row and the corner button report the shades too, the same
     * filled glyphs the top bar's buttons wear; the ARGB is the token `CollapsedChrome` compares.
     */
    override fun collapsedPenKinds(): CollapsedChrome.PenKinds = CollapsedChrome.PenKinds(
        kinds = SketchToolState.Kind.entries.map { kind ->
            CollapsedChrome.PenKinds.Kind(iconResOf(kind), getString(hintOf(kind))) {
                val ink = toolbar.state.report(kind)
                CollapsedChrome.PenIcon(ink) { glyphOf(kind, ink) }
            }
        },
        armed = { toolbar.state.kind.ordinal },
        onPick = { index -> armPen(SketchToolbar.kindAt(index)) },
        onReTap = { _, anchor -> togglePaletteBar(anchor) },
    )

    private fun iconResOf(kind: SketchToolState.Kind): Int = when (kind) {
        SketchToolState.Kind.PENCIL -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_pencil
        SketchToolState.Kind.PEN -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_pen
        SketchToolState.Kind.MARKER -> com.symmetricalpalmtree.soil.paper.R.drawable.ic_marker
    }

    private fun hintOf(kind: SketchToolState.Kind): Int = when (kind) {
        SketchToolState.Kind.PENCIL -> R.string.cd_tool_pencil
        SketchToolState.Kind.PEN -> R.string.cd_tool_pen
        SketchToolState.Kind.MARKER -> R.string.cd_tool_marker
    }

    private fun glyphOf(kind: SketchToolState.Kind, ink: Int): android.graphics.drawable.Drawable = when (kind) {
        SketchToolState.Kind.PENCIL -> ShadeIcon.pencil(this, ink)
        SketchToolState.Kind.PEN -> ShadeIcon.pen(this, ink)
        SketchToolState.Kind.MARKER -> ShadeIcon.marker(this, ink)
    }

    /** The top bar's button for [kind] — the re-tap's anchor and the outside-tap rule's toggler. */
    private fun buttonOf(kind: SketchToolState.Kind): ImageButton = when (kind) {
        SketchToolState.Kind.PENCIL -> binding.btnPencil
        SketchToolState.Kind.PEN -> binding.btnPen
        SketchToolState.Kind.MARKER -> binding.btnMarker
    }

    /** The shade panel and the guides panel are this screen's own floating chrome: the pen refuses
     *  under them and a finger landing on them is not a page gesture. */
    override fun extraFloatingRects(): List<Rect> = (paletteBar?.rects() ?: emptyList()) + (guides?.rects() ?: emptyList())

    override fun extraFloatingContains(x: Int, y: Int): Boolean = paletteBar?.contains(x, y) == true || guides?.contains(x, y) == true

    /** The mini toolbar's rows are coming down — a panel hung under one of them goes with them.
     *  No exclusion push here: [CollapsedChrome]'s own `onChanged` follows. */
    override fun onCollapsedClosing() { takeDownPaletteBar(); guides?.takeDown() }

    /** …and a contact **inside** either panel must not take the rows down under it. */
    override fun keepCollapsedUnder(x: Int, y: Int): Boolean = paletteBar?.contains(x, y) == true || guides?.contains(x, y) == true

    override fun hideFloatingBars() {
        super.hideFloatingBars()
        hidePaletteBar()
        guides?.hide()
    }

    /** Back · Guides — the top bar's doors, mirrored. Guides takes an `onTap`: the top bar's
     *  button is `GONE` while collapsed, so the panel hangs under this row's button instead, and
     *  the rows stay up beneath it. */
    override fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(
        backEntry(),
        CollapsedChrome.Entry.mirroring(com.symmetricalpalmtree.soil.paper.R.drawable.ic_grid, binding.btnGuides) { anchor -> toggleGuidesBar(anchor) },
    )

    /** The sketchbook opens with its bars as they were left. */
    override val initialChromeHidden: Boolean get() = prefs.chromeHidden

    override fun onChromeChanged(hidden: Boolean) { prefs.chromeHidden = hidden }

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SketchPrefs(this)
        binding = ActivitySketchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        // **Before any content**: the mode drops what the view holds, exactly as a page turn does,
        // and it is never flipped under ink.
        paper.pageMode = PageMode.RASTER
        // No pen gestures on a sketch: a hatch is not a scribble and a closed shading loop is not a
        // selection. Armed before the listener attaches — the engine reads them as it wires up.
        paper.smartLassoEnabled = false
        paper.scribbleEraseEnabled = false
        paper.setPaperListener(paperListener)
        Slog.d(TAG) { "engine=${paper.engineId}" }

        saver = SketchSaver(
            copyPage = { layer -> paper.getPageRaster(layer) },
            awaitPenIdle = { paper.awaitPenIdle() },
            write = { pageKey, layer, bytes ->
                val s = store ?: throw IllegalStateException("no store")
                s.writeRaster(pageKey, layer, bytes)
            },
            isTooLarge = { it is SketchbookStore.RasterTooLarge },
            onTooLarge = { layer ->
                if (!isFinishing && !isDestroyed) {
                    Dialogs.problem(
                        this, R.string.sketch_too_large_title,
                        when (layer) {
                            RasterLayer.GRAPHITE -> R.string.sketch_too_large_graphite_body
                            RasterLayer.INK -> R.string.sketch_too_large_ink_body
                            RasterLayer.MARKER -> R.string.sketch_too_large_marker_body
                        },
                    )
                }
            },
        )

        toolbar = SketchToolbar(
            paper = paper,
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPencil = binding.btnPencil,
            btnPen = binding.btnPen,
            btnMarker = binding.btnMarker,
            btnEraser = binding.btnEraser,
            btnSmudge = binding.btnSmudge,
            btnGuides = binding.btnGuides,
            title = binding.title,
            pageIndicator = binding.pageIndicator,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            onBack = { exit() },
            onGuides = { toggleGuidesBar(binding.btnGuides) },
            onPrevPage = { runPageOp { turnPageNow(PageTurn.Direction.PREV) } },
            // Past the last page the arrow makes one, as the swipe does (Greg, 2026-10-07).
            onNextPage = { gestureListener.onFlipNext() },
            // An actual tool change — including a switch between pen kinds, which never moves
            // `paper.tool`: the palette shows the kind that is leaving.
            onToolTapped = { dismissCollapsed(); hidePaletteBar(); guides?.hide() },
            // The armed kind's own button.
            onPenReTap = { kind -> togglePaletteBar(buttonOf(kind)) },
            onPenKindPicked = { kind -> pickTools(toolbar.state.withKind(kind)) },
            onSynced = { syncCollapsed() },   // the corner button repaints with the bar
        )
        // The panel edits the ARMED KIND's shade; the bar itself is :paper's and edits a level —
        // this screen says whose.
        paletteBar = PaletteBar(
            root = binding.root,
            bar = binding.paletteBar,
            anchor = binding.btnPencil,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            armedLevel = { toolbar.state.armedShade },
            onPicked = { level -> pickTools(toolbar.state.withShade(level)) },
            // The armed kind's ladder (Greg, 2026-10-08): the sample is the stroke at its real
            // width, the marker's at the marker's translucency.
            sizes = {
                val kind = toolbar.state.kind
                SketchPalette.ladder(kind).sizes.map { size ->
                    PaletteBar.SizeOption(
                        size.px,
                        getString(com.symmetricalpalmtree.soil.paper.R.string.cd_size, size.label),
                        alpha = if (kind == SketchToolState.Kind.MARKER) MARKER_SAMPLE_ALPHA else 255,
                        badge = size.badge,
                    )
                }
            },
            armedSize = { toolbar.state.armedSize },
            onSizePicked = { index -> pickTools(toolbar.state.withSize(index)) },
        )
        // Registers its picker here, in `onCreate`, before the screen is started — the
        // activity-result contract's one rule.
        guides = SketchGuides(
            activity = this,
            paper = paper,
            root = binding.root,
            barView = binding.guidesBar,
            anchor = binding.btnGuides,
            bandBottom = { chromeBand()?.last },
            store = { store },
            usable = { opened && !closing },
            onBarChanged = { pushExclusions() },
            onSheet = { sheet -> paper.setSheet(sheet); currentSheet = sheet },
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            // The surface accepts no ink until the page is truly on it: a mark composited now would
            // be thrown away by the load that follows, with nowhere to have been recorded.
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { smudge.active },   // an armed smudge rub owns the finger
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.root.post { centreTitleInTheFreeBand(); pushExclusions() }
        }
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        lifecycleScope.launch { openSketchbook() }
    }

    /** A second open while this one shows: the same sketchbook is nothing; another is a flush and
     *  a rebuild, the notebook's shape. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        if (newName.isNullOrBlank() && askedId != null && askedId == itemId) return
        setIntent(intent)
        if (closing) return
        closing = true
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { saver.flushAndAwait() }.onFailure { Log.w(TAG, "flush failed: ${it.javaClass.simpleName}") } }
            }
            if (!isFinishing && !isDestroyed) {
                paper.releaseForHandoff()
                recreate()
            }
        }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openSketchbook() {
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (newName.isNullOrBlank() && askedId.isNullOrEmpty()) {
            refuse(R.string.open_missing)
            return
        }
        val soil = (application as SketchsproutApp).soil
        val (w, h) = surfaceSize()
        val loadedBook: Pair<SketchbookStore.Loaded, String> = try {
            withContext(Dispatchers.IO) {
                val seam = soil.seam()
                val item = if (!newName.isNullOrBlank()) seam.createItem(newName, SketchbookSchema.SCHEMA)
                else seam.item(askedId!!) ?: throw IllegalStateException(NO_SUCH_ITEM)
                val open = seam.openItem(item.id, SketchbookSchema.SCHEMA, owner)
                session = open
                itemId = item.id
                val s = SketchbookStore(SeamRowStore(open), item.id)
                store = s
                // A file Soil made and nobody has written yet has no pages: it gets its first here,
                // the size of this surface, like a sketchbook made by name.
                val loaded = if (!newName.isNullOrBlank()) {
                    s.initialize(item.name, w, h)
                } else {
                    try {
                        s.load()
                    } catch (e: SketchbookStore.NoPages) {
                        s.initialize(item.name, w, h)
                    }
                }
                // The library learns the page order at every open: it names a page by it without
                // ever opening the file.
                runCatching { seam.setPages(item.id, loaded.pages.map { it.id }) }.onFailure { Log.w(TAG, "the pages were not told: ${it.javaClass.simpleName}") }
                // Soil's New sketchbook chose the paper: the first page takes it, as any pick is
                // taken, and not as an undo step. Consumed once.
                var pages = loaded.pages
                intent.getStringExtra(Seam.EXTRA_TEMPLATE_PICK)?.let { encoded ->
                    intent.removeExtra(Seam.EXTRA_TEMPLATE_PICK)
                    val pick = TemplatePick.decode(encoded)
                    if (pick != null && pick !is TemplatePick.Blank) {
                        runCatching {
                            val paperSource = paperOf(pick)
                            val first = pages.first()
                            if (paperSource != null) {
                                s.changeTemplate(first, paperSource, resources.displayMetrics.densityDpi.toFloat())?.let { id ->
                                    pages = pages.map { if (it.id == first.id) it.copy(templateId = id) else it }
                                }
                                runCatching { seam.templateUsed(pick.cardId) }
                            }
                        }.onFailure { Log.w(TAG, "the new sketchbook's paper could not be laid: ${it.javaClass.simpleName}") }
                    }
                }
                // Read again, never once per process: another screen may have copied since.
                SketchClipboard.refresh(seam)
                prefs.lastSketchbookId = item.id
                SketchbookStore.Loaded(pages, loaded.currentId) to item.name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the sketchbook could not be opened: ${e.javaClass.simpleName}")
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
        pages = loadedBook.first.pages
        toolbar.setTitle(loadedBook.second)
        // Opened via a link or the library's page search: land on the page named, once.
        val asked = intent.getStringExtra(Seam.EXTRA_PAGE_ID)?.also { intent.removeExtra(Seam.EXTRA_PAGE_ID) }
        val first = pages.firstOrNull { it.id == asked } ?: pages.firstOrNull { it.id == loadedBook.first.currentId } ?: pages.first()
        loadPage(first, firstLoad = true)
        if (isFinishing || isDestroyed || closing) return
        // Before `opened`, which is what the block-all rect waits on: the tools are in place before
        // the first mark is possible, so nothing is ever drawn with a pencil the person did not
        // choose. Always a pen kind: the rubber and the smudge are never remembered.
        toolbar.apply(prefs.tools)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // The page is on the paper — take the box down. Deliberately NOT pen-idle-gated:
        // `isPenActive` counts hover, and the pen is already over the glass on the way to drawing.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page ${pages.indexOf(first) + 1}/${pages.size} open" }
    }

    /**
     * Put [page] on the paper — **both of its rasters**: read each one's WebP from the store,
     * decode it, and load it onto that layer (`clearForContentSwap` → `setPageSize` → the content
     * calls, one EPD refresh, no blank flash).
     *
     * **Both layers are always loaded, null for an absent one**, so the screen never has to
     * reason about which rasters the *previous* page had. **Both decoded first, then both loaded
     * back-to-back**: the engine rebuilds its dithered display at every load, so two loads with a
     * decode between them showed the pencil first and the ink a moment later; together they fold
     * into one rebuild. The header guard runs before each decode ([RasterImage.decode]).
     *
     * **A page with a save still in the air waits for it first** ([SketchSaver.awaitPushes]): a
     * turn straight back to a page drawn on seconds ago would otherwise read the row as it was
     * before the last strokes.
     */
    private suspend fun loadPage(page: SketchPage, firstLoad: Boolean) {
        // A contact that never got its pen-up leaves a half-gathered entry tagged with the page
        // being left; carried across the turn it would go on collecting the next page's cells
        // under the old page's key. The honest loss.
        openEdit = null
        if (saver.isPushPending(page.id)) {
            val t0 = SystemClock.elapsedRealtime()
            saver.awaitPushes(page.id)
            Slog.d(TAG) { "waited ${SystemClock.elapsedRealtime() - t0} ms for this page's own save before loading it" }
        }
        dismissCollapsed()   // a floating row never survives a content swap
        hidePaletteBar()     // nor a panel hung under one
        guides?.hide()       // nor the guides panel — it is this page's
        val width = page.width.toInt(); val height = page.height.toInt()
        val s = store ?: return
        // **Everything is read and decoded before the paper is touched**: the page's paper
        // and both rasters, side by side on IO — two reads over the seam and two WebP decodes,
        // each hundreds of milliseconds on a drawn page. The outgoing page stays on the glass
        // the whole time, and what follows is one synchronous run of engine calls.
        val t0 = SystemClock.elapsedRealtime()
        val paperBitmap = paperFor(page)
        // The sheet: the paper, the page's reference image and its grid, one bitmap.
        val sheet = guides?.load(page, paperBitmap) ?: paperBitmap
        val decoded = withContext(Dispatchers.IO) {
            RasterRows.LAYERS.map { layer ->
                async {
                    val bytes = runCatching { s.readRaster(page.id, layer) }
                        .onFailure { Log.w(TAG, "the page's ${RasterRows.name(layer)} raster could not be read: ${it.javaClass.simpleName}") }
                        .getOrNull()
                    layer to RasterImage.decode(bytes, width, height)
                }
            }.awaitAll()
        }
        val decodedMs = SystemClock.elapsedRealtime() - t0
        if (isFinishing || isDestroyed) { decoded.forEach { it.second?.recycle() }; return }
        // Now the swap, in one breath with no suspension inside it: the clear, the size, the
        // sheet and both rasters announce the whole page, and the engine's coalescer folds every
        // announcement made before the next loop into ONE rebuild and one present (a yield in
        // here, such as a decode, let the sheet present alone and the drawing a frame later).
        val t1 = SystemClock.elapsedRealtime()
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(width, height)
        // The paper is the SHEET: the direct raster path never flattens a template. Set only
        // when it changes — the engine holds it by reference across loads.
        if (sheet !== currentSheet) { paper.setSheet(sheet); currentSheet = sheet }
        for ((layer, bitmap) in decoded) {
            // Silent: a page we loaded ourselves is our own news, so no will-change/changed pair
            // arrives and nothing here has to swallow one.
            paper.loadPageRaster(layer, bitmap)
            bitmap?.recycle()
        }
        Slog.d(TAG) { "page load: read+decode $decodedMs ms, swap ${SystemClock.elapsedRealtime() - t1} ms" }
        currentPage = page
        saver.pageKey = page.id
        saver.markClean()
        toolbar.setPage(pages.indexOf(page) + 1, pages.size)
    }

    // ── Paper ────────────────────────────────────────────────────────────────

    /** [page]'s paper as a page-sized bitmap, from the cache or decoded now; null for blank. */
    private suspend fun paperFor(page: SketchPage): Bitmap? {
        val id = page.templateId.takeIf { it.isNotEmpty() } ?: return null
        paperCache[id]?.let { return it }
        val s = store ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { Bitmaps.decodeBounded(s.templateBlob(id), MAX_TEMPLATE_EDGE) }
                .onFailure { Log.w(TAG, "the page's paper could not be read: ${it.javaClass.simpleName}") }
                .getOrNull()
        } ?: return null
        if (paperCache.size >= PAPER_CACHE_SIZE) paperCache.remove(paperCache.keys.first())
        paperCache[id] = bitmap
        return bitmap
    }

    /** The pick names a card; the pixels are read here, through the seam. IO only. */
    private suspend fun paperOf(pick: TemplatePick): PaperSource? = when (pick) {
        TemplatePick.Blank -> PaperSource.Blank
        is TemplatePick.BuiltIn -> PaperSource.BuiltIn(pick.kind)
        is TemplatePick.Static -> try {
            val seam = (application as SketchsproutApp).soil.seam()
            val info = seam.template(pick.id)
            if (info == null) null else PaperSource.Image(SeamShared.readAndClose(seam.templateImage(pick.id)), TemplateFit.sanitize(info.fit))
        } catch (e: Exception) {
            Log.w(TAG, "the template could not be read: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Page template on the sheet: Soil's picker, started for a result with the page's token so
     *  the paper in force is ticked. */
    private fun openTemplatePicker() {
        if (!opened || closing || soilScreenShowing) return
        val page = currentPage ?: return
        val s = store ?: return
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) { runCatching { PageTemplate.tokenOf(s.templateDigests(), page.templateId) }.getOrNull() }
            if (!opened || closing || soilScreenShowing) return@launch
            val intent = Intent(Seam.ACTION_PICK_TEMPLATE)
                .setPackage(BuildConfig.SOIL_PACKAGE)
                .putExtra(Seam.EXTRA_CURRENT_TOKEN, token)
            startSoilScreen { templatePickerLauncher.launch(intent) }
        }
    }

    private fun startSoilScreen(launch: () -> Unit) {
        soilScreenShowing = true
        hideFloatingBars()
        dismissCollapsed()
        inAppHandoff = true
        paper.releaseForHandoff()
        try {
            launch()
        } catch (e: Exception) {
            Log.w(TAG, "Soil's screen would not open: ${e.javaClass.simpleName}")
            onSoilScreenClosed()
            Dialogs.problem(this, R.string.open_failed_title, R.string.open_no_soil)
        }
    }

    /** The result callback runs before `onResume`: the pipeline is reclaimed first of all. */
    private fun onSoilScreenClosed() {
        soilScreenShowing = false
        inAppHandoff = false
        if (opened) paper.resumeDrawing()
    }

    private fun onTemplatePicked(resultCode: Int, encoded: String?) {
        onSoilScreenClosed()
        if (resultCode != android.app.Activity.RESULT_OK) return
        // A pick this build cannot read is a cancel, never Blank.
        val pick = TemplatePick.decode(encoded) ?: return
        applyPick(pick)
    }

    /** The page takes the pick: one undo step, the sheet re-set under the rasters in place. */
    private fun applyPick(pick: TemplatePick) {
        val page = currentPage ?: return
        val s = store ?: return
        runPageOp {
            if (currentPage?.id != page.id) return@runPageOp
            val source = withContext(Dispatchers.IO) { paperOf(pick) }
            if (source == null) {
                Dialogs.problem(this@SketchActivity, R.string.template_gone_title, R.string.template_gone_body)
                return@runPageOp
            }
            val target = try {
                withContext(Dispatchers.IO) { s.changeTemplate(page, source, resources.displayMetrics.densityDpi.toFloat()) }
            } catch (e: SketchbookStore.PaperRenderFailed) {
                Dialogs.problem(this@SketchActivity, R.string.template_render_failed_title, R.string.template_render_failed_body)
                return@runPageOp
            } catch (e: Exception) {
                showProblem(R.string.page_failed_title, R.string.page_failed_body)
                return@runPageOp
            }
            // An apply is the one thing that makes paper recent, re-picking the paper in force included.
            withContext(Dispatchers.IO) { runCatching { (application as SketchsproutApp).soil.seam().templateUsed(pick.cardId) } }
            if (target == null) return@runPageOp
            undo.record(SketchEdit.TemplateChanged(page.id, pages.indexOf(page), page.templateId, target))
            applyTemplate(page.id, target)
        }
    }

    /** Point [pageId] at [templateId] in the page list and, when it is the page showing, under
     *  the rasters. The file was already written. */
    private suspend fun applyTemplate(pageId: String, templateId: String) {
        pages = pages.map { if (it.id == pageId) it.copy(templateId = templateId) else it }
        val here = currentPage ?: return
        if (here.id != pageId) return
        val updated = here.copy(templateId = templateId)
        currentPage = updated
        val paperBitmap = paperFor(updated)
        paper.awaitPenIdle()
        guides?.setPaper(paperBitmap)
    }

    // ── Page turns, inserts and deletes ──────────────────────────────────────

    /**
     * One page along, or stay put at the edge. **This page's pixels are frozen first, not written
     * first** ([SketchSaver.flushForTurn]): the copy is taken before anything moves and the encode
     * goes on in the background; [loadPage] waits for a page's own writes before reading it back.
     */
    private suspend fun turnPageNow(direction: PageTurn.Direction) {
        val here = currentPage ?: return
        val i = pages.indexOf(here)
        val target = PageTurn.targetIndex(i, pages.size, direction) ?: run {
            Slog.d(TAG) { "page turn refused at the edge (page ${i + 1}/${pages.size})" }
            return
        }
        if (!saver.flushForTurn()) Log.w(TAG, "the page's pixels could not be copied before the turn; the raster stays dirty")
        val to = pages[target]
        loadPage(to, firstLoad = false)
        rememberLastOpened(to)
        Slog.d(TAG) { "turned to page ${target + 1}/${pages.size}" }
    }

    private suspend fun rememberLastOpened(page: SketchPage) {
        val s = store ?: return
        withContext(Dispatchers.IO) { runCatching { s.setLastOpened(page.id) }.onFailure { Log.w(TAG, "the last page was not remembered: ${it.javaClass.simpleName}") } }
    }

    /** The library learns the page order at every change: it names a page by it without opening the file. */
    private suspend fun tellPages() {
        val id = itemId ?: return
        val ids = pages.map { it.id }
        withContext(Dispatchers.IO) { runCatching { (application as SketchsproutApp).soil.seam().setPages(id, ids) }.onFailure { Log.w(TAG, "the pages were not told: ${it.javaClass.simpleName}") } }
    }

    /** A blank page on the side [after] names, landed on. **This page's pixels go first**, awaited:
     *  the row is about to be read beside it. Recorded as one structural entry. */
    private suspend fun insertPageNow(after: Boolean) {
        val here = currentPage ?: return
        val s = store ?: return
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the insert; its pixels stay parked")
        val before = pages
        val (next, page) = try {
            withContext(Dispatchers.IO) { s.insertPage(before, here.id, after) }
        } catch (e: Exception) {
            Log.w(TAG, "the page could not be inserted: ${e.javaClass.simpleName}")
            showProblem(R.string.page_failed_title, R.string.page_failed_body)
            return
        }
        pages = next
        val at = next.indexOf(page)
        undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        // Recorded AFTER the re-index, so the entry is never shifted by its own insert — and
        // through `record`, which clears the redo side: a new edit forks the history here.
        undo.record(SketchEdit.PagesChanged(SketchEdit.PagesChanged.Kind.INSERTED, page.id, at, before, next, emptyList(), here.id, page.id))
        loadPage(page, firstLoad = false)
        tellPages()
        Slog.d(TAG) { "inserted page ${at + 1}/${next.size}" }
    }

    /**
     * Delete the page on the glass and land on the one before it. **The doomed page IS flushed
     * first**: what the undo brings back is whatever was last saved, so a mark made just before
     * the long-press must be in the row. The only page is replaced by a fresh blank one.
     */
    private suspend fun deletePageNow() {
        val here = currentPage ?: return
        val s = store ?: return
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the delete; its pixels stay parked")
        saver.cancelTimers()
        saver.markClean()
        val before = pages
        val at = before.indexOf(here)
        val (next, landing, taken) = try {
            withContext(Dispatchers.IO) { s.deletePage(before, here) }
        } catch (e: Exception) {
            Log.w(TAG, "the page could not be deleted: ${e.javaClass.simpleName}")
            showProblem(R.string.page_failed_title, R.string.page_failed_body)
            return
        }
        pages = next
        // Pixel entries for the page that went are dropped by KEY (the only thing that certainly
        // names it); page entries are never dropped, so insert-then-delete undoes twice, honestly.
        undo.remap { edit ->
            if (edit is SketchEdit.RasterChanged && edit.pageKey == here.id) null
            else edit.withIndex(PageTurn.reindexAfterDelete(edit.pageIndex, at))
        }
        undo.record(SketchEdit.PagesChanged(SketchEdit.PagesChanged.Kind.DELETED, here.id, at, before, next, taken, here.id, landing.id))
        loadPage(landing, firstLoad = false)
        tellPages()
        Slog.d(TAG) { "deleted a page; now page ${next.indexOf(landing) + 1}/${next.size}" }
    }

    /** The links panel while it is up; one showing at a time. */
    private var backlinksPanel: BacklinksPanel<SeamBacklink>? = null

    /** The page sheet, on a finger long-press: it asks; it never acts. A Paste row is present
     *  while its kind of clip is there, absent otherwise — never disabled; and, when anything in
     *  the library links to this page or to the whole sketchbook, what links here. */
    private fun showPageSheet() {
        if (!opened || closing) return
        val page = currentPage ?: return
        paper.releaseRender()
        lifecycleScope.launch {
            val into = backlinksTo(page.id)
            if (!opened || closing || currentPage?.id != page.id) return@launch
            buildPageSheet(into).show()
        }
    }

    /** What in the library links to [pageId], or to the whole sketchbook, by source name. */
    private suspend fun backlinksTo(pageId: String): List<SeamBacklink> {
        val me = itemId ?: return emptyList()
        return withContext(Dispatchers.IO) { runCatching { (application as SketchsproutApp).soil.seam().backlinks(me) }.getOrDefault(emptyList()) }
            .filter { it.targetPageId == null || it.targetPageId == pageId }
            .sortedWith(compareBy({ it.targetPageId == null }, { it.sourceName.lowercase() }))
    }

    private fun showBacklinks(into: List<SeamBacklink>) {
        if (!opened || closing || backlinksPanel != null) return
        hideFloatingBars()
        dismissCollapsed()
        paper.releaseRender()
        val pageWord = getString(R.string.backlinks_notebook_page)
        val wholeWord = getString(R.string.backlinks_whole_item)
        val documentWord = getString(com.symmetricalpalmtree.soil.paper.R.string.backlinks_document_word)
        backlinksPanel = BacklinksPanel(
            this,
            title = getString(R.string.backlinks_title),
            emptyText = getString(R.string.backlinks_empty),
            rows = into.map { b ->
                val where = if (b.sourceKind == "notebook") pageWord else documentWord
                BacklinksModel.Row(b, "${b.sourceName} · $where", if (b.targetPageId == null) wholeWord else "")
            },
            onDismissed = { backlinksPanel = null; pushExclusions() },
            onPicked = ::followBacklink,
        ).also { it.show() }
    }

    /** Go to where the link was made: Soil opens the page, or the document, in its app over this
     *  screen, and Back comes back here. */
    private fun followBacklink(b: SeamBacklink) {
        if (!opened || closing) return
        hideFloatingBars()
        dismissCollapsed()
        val started = runCatching {
            startActivity(
                Intent(Seam.ACTION_FOLLOW).setPackage(BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_ITEM_ID, b.sourceItemId)
                    .putExtra(Seam.EXTRA_PAGE_ID, b.sourcePageId.takeIf { it.isNotEmpty() }),
            )
        }.isSuccess
        if (!started) Dialogs.problem(this, R.string.link_follow_failed_title, R.string.link_follow_failed_body)
    }

    private fun buildPageSheet(into: List<SeamBacklink>): ActionSheetDialog {
        val sheet = ActionSheetDialog(this)
            .title(getString(R.string.page_sheet_title))
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_copy, getString(R.string.copy_page_action)) { runPageOp { doCopyPage() } }
        if (SketchClipboard.hasPage) sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_clipboard, getString(R.string.paste_page_action)) { showPasteSheet() }
        if (SketchClipboard.hasInk) sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_pen, getString(R.string.paste_ink_action)) { runPageOp { doPasteInk() } }
        sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_template, getString(R.string.page_template_action)) { openTemplatePicker() }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, getString(R.string.page_sheet_delete)) { confirmDeletePage() }
        if (into.isNotEmpty()) {
            sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_link, resources.getQuantityString(R.plurals.page_sheet_backlinks, into.size, into.size)) { showBacklinks(into) }
        }
        sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_export, getString(R.string.export_page_action)) { exportVia(currentPage?.id) }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_export, getString(R.string.export_sketchbook_action)) { exportVia(null) }
        return sheet
    }

    // ── The clipboard ────────────────────────────────────────────────────────

    private suspend fun seam() = withContext(Dispatchers.IO) { (application as SketchsproutApp).soil.seam() }

    /** Both headers read again, off Main: the clipboard is the library's, and anything may have
     *  copied to it while this screen was behind. */
    private fun refreshClipboard() {
        if (!opened || closing) return
        lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { SketchClipboard.refresh((application as SketchsproutApp).soil.seam()) } } }
    }

    /** Copy page: the page's rasters written first, then the page and everything under it onto
     *  the sketchbook slot. Anything that did not work is a dialog: a copy that failed silently
     *  would leave a stale clipboard standing ready to paste the wrong page. */
    private suspend fun doCopyPage() {
        val page = currentPage ?: return
        val s = store ?: return
        val id = itemId ?: return
        if (!saver.flushAndAwait()) { Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_capture_failed); return }
        val bytes = runCatching { withContext(Dispatchers.IO) { s.capturePage(page) } }
            .onFailure { Log.w(TAG, "page capture failed: ${it.javaClass.simpleName}") }.getOrNull()
        if (bytes == null) { Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_capture_failed); return }
        if (bytes.size > SeamLimits.MAX_VALUE_BYTES) { Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_too_large); return }
        val write = runCatching { withContext(Dispatchers.IO) { SketchClipboard.writePage((application as SketchsproutApp).soil.seam(), id, bytes, System.currentTimeMillis()) } }
            .onFailure { Log.w(TAG, "clipboard write failed: ${it.javaClass.simpleName}") }
        if (write.isFailure) { Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_write_failed); return }
        toast(getString(R.string.page_copied_toast))
        Slog.d(TAG) { "page copied: ${bytes.size} B" }
    }

    /** Before or after this page (Greg, 2026-10-07): the notebook's small sheet. */
    private fun showPasteSheet() {
        if (!opened || closing) return
        ActionSheetDialog(this)
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_prev, getString(R.string.paste_before_action)) { runPageOp { doPastePage(before = true) } }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_next, getString(R.string.paste_after_action)) { runPageOp { doPastePage(before = false) } }
            .show()
    }

    /** Paste the clipboard's page beside this one and land on it: one structural entry. */
    private suspend fun doPastePage(before: Boolean) {
        val here = currentPage ?: return
        val s = store ?: return
        val rows = runCatching { withContext(Dispatchers.IO) { SketchPageClip.decode(SketchClipboard.readPage((application as SketchsproutApp).soil.seam())) } }.getOrNull()
        if (rows == null) {
            // Gone, foreign, or claiming a page it does not carry: stop advertising a Paste that
            // cannot work, in memory and in Soil.
            withContext(Dispatchers.IO) { runCatching { SketchClipboard.clearPage((application as SketchsproutApp).soil.seam()) } }
            Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_paste_failed)
            return
        }
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the paste; its pixels stay parked")
        val before0 = pages
        val (next, page) = try {
            withContext(Dispatchers.IO) { s.pastePage(before0, here.id, before, rows) } ?: run {
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_paste_failed); return
            }
        } catch (e: Exception) {
            Log.w(TAG, "the page could not be pasted: ${e.javaClass.simpleName}")
            showProblem(R.string.page_failed_title, R.string.page_failed_body)
            return
        }
        pages = next
        val at = next.indexOf(page)
        undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        undo.record(SketchEdit.PagesChanged(SketchEdit.PagesChanged.Kind.INSERTED, page.id, at, before0, next, emptyList(), here.id, page.id))
        loadPage(page, firstLoad = false)
        tellPages()
        val anchor = PageMath.anchorNumberAfterPaste(before0.indexOf(here), before, 1)
        toast(getString(if (before) R.string.pasted_before_toast else R.string.pasted_after_toast, anchor))
        Slog.d(TAG) { "pasted a page ${if (before) "before" else "after"}: now page ${at + 1}/${next.size}" }
    }

    /**
     * Paste ink: the notebook slot's strokes — a lasso's, the pad's, a copied page's — laid
     * **centred** on this page and baked **black into the ink layer** (Greg, 2026-10-07), one undo
     * entry, as a bake: the builder opens on the layer the engine names and the door closes it.
     */
    private suspend fun doPasteInk() {
        val page = currentPage ?: return
        val env = runCatching { withContext(Dispatchers.IO) { SketchClipboard.readInk((application as SketchsproutApp).soil.seam()) } }.getOrNull()
        if (env == null) { Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_paste_ink_failed); return }
        val black = InkClip.strokesOf(env).map { it.copy(color = 0xFF000000.toInt(), style = StrokeStyle.PEN) }
        val placed = InkPlacement.centred(black, page.width, page.height) { java.util.UUID.randomUUID().toString() }
        if (placed.isEmpty()) { Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_paste_ink_empty); return }
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return
        closeOpenEdit()
        try {
            paper.addStrokes(placed)
        } finally {
            closeOpenEdit()
            toolbar.restorePen()
        }
        saver.markDirty(RasterLayer.INK)
        saver.schedule()
        toast(getString(R.string.ink_pasted_toast))
        Slog.d(TAG) { "pasted ${placed.size} stroke(s) as ink" }
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** Soil's export screen for this sketchbook, or one page of it. The sketchbook closes first,
     *  so the file is free for the renderer, and Soil opens it again on the way back. */
    private fun exportVia(pageId: String?) {
        if (!opened || closing) return
        val id = itemId ?: return
        afterExit = {
            startActivity(
                Intent(Seam.ACTION_EXPORT).setPackage(BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_ITEM_ID, id)
                    .putExtra(Seam.EXTRA_PAGE_ID, pageId)
                    .putExtra(Seam.EXTRA_RETURN_TO_APP, true),
            )
        }
        exit()
    }

    private fun confirmDeletePage() {
        if (!opened || closing) return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_page_title)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { deletePageNow() } }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        ).show()
    }

    // ── Undo and redo ────────────────────────────────────────────────────────

    /**
     * Take one edit back — or put it back — by swapping pixels, **and never by reloading the
     * page**: a reload would read the stored row, which still holds the image as it was before the
     * swap, and put it straight back. A pixel entry made on another page walks back to it first
     * ([walkTo]); the pen-idle gate is waited on; a mark that landed while it waited puts the entry
     * back **beneath** what landed, and the gesture simply has to be repeated.
     */
    private suspend fun doReplay(undoing: Boolean) {
        closeOpenEdit()   // a smudge entry held open for the chatter window is written first
        val edit = (if (undoing) undo.popUndo() else undo.popRedo()) ?: return
        val generation = undo.generation
        val applied = try {
            when (edit) {
                is SketchEdit.RasterChanged -> applyEdit(edit, generation, undoing)
                is SketchEdit.PagesChanged -> applyPages(edit, generation, undoing)
                is SketchEdit.TemplateChanged -> applyTemplateEdit(edit, generation, undoing)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Failed mid-replay: put the entry back so the history never silently loses a step.
            if (undoing) undo.pushUndo(edit) else undo.pushRedo(edit)
            throw t
        }
        if (!applied) return
        if (undoing) undo.pushRedo(edit) else undo.pushUndo(edit)
    }

    /** Whether the swap actually landed — false means the entry was put back, or dropped. */
    private suspend fun applyEdit(edit: SketchEdit.RasterChanged, generation: Int, undoing: Boolean): Boolean {
        val here = currentPage ?: return false
        if (edit.pageKey != here.id && !walkTo(edit)) {
            Log.w(TAG, "an edit's page could not be reached; the entry was dropped")
            return false
        }
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            Slog.d(TAG) { "a mark landed while the replay waited for the pen; the entry was put back beneath it" }
            return false
        }
        val tiles = if (undoing) edit.tiles.asReversed() else edit.tiles
        // The entry's own layer: a `RasterPatch` carries none.
        paper.swapPageRaster(edit.layer, tiles.map { RasterPatch(Rect(it.left, it.top, it.left + it.width, it.top + it.height), it.pixels) })
        saver.markDirty(edit.layer)
        saver.schedule()
        Slog.d(TAG) { "${if (undoing) "undo" else "redo"}: ${edit.tiles.size} ${RasterRows.name(edit.layer)} tiles swapped" }
        return true
    }

    /** Take back — or put back — one page insert or delete: one reconcile of the file to the list
     *  on the other side, landing where that side landed. This page's pixels go first, awaited. */
    private suspend fun applyPages(edit: SketchEdit.PagesChanged, generation: Int, undoing: Boolean): Boolean {
        if (currentPage == null) return false
        val s = store ?: return false
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the page replay; its pixels stay parked")
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            Slog.d(TAG) { "a mark landed while the page replay waited for the pen; the entry was put back beneath it" }
            return false
        }
        val target = if (undoing) edit.before else edit.after
        val landing = if (undoing) edit.landingBefore else edit.landingAfter
        val restore = if (undoing) edit.takenIds else emptyList()
        val delete = if (undoing) emptyList() else edit.takenIds
        try {
            withContext(Dispatchers.IO) { s.reconcile(pages, target, restore, delete, landing) }
        } catch (e: Exception) {
            showProblem(R.string.page_failed_title, R.string.page_failed_body)
            throw e
        }
        pages = target
        // The entry's own index is the position in both directions, so an undo and the redo after
        // it are exact inverses. Pixel entries are never dropped by a replay — only a fresh delete
        // drops them: marks made on an inserted page sit on the redo side waiting for its return.
        val at = edit.pageIndex
        val back = if (undoing) edit.kind == SketchEdit.PagesChanged.Kind.DELETED else edit.kind == SketchEdit.PagesChanged.Kind.INSERTED
        if (back) undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        else undo.remap { it.withIndex(PageTurn.reindexAfterDelete(it.pageIndex, at)) }
        val to = target.firstOrNull { it.id == landing } ?: target.first()
        loadPage(to, firstLoad = false)
        tellPages()
        Slog.d(TAG) { "${if (undoing) "undo" else "redo"} of a page ${edit.kind.name.lowercase()}: now page ${target.indexOf(to) + 1}/${target.size}" }
        return true
    }

    /** Take back — or put back — a re-papering: the page points at the other row, in the file
     *  and under the rasters. Walks to the page first, as a pixel entry does. */
    private suspend fun applyTemplateEdit(edit: SketchEdit.TemplateChanged, generation: Int, undoing: Boolean): Boolean {
        val here = currentPage ?: return false
        val s = store ?: return false
        if (edit.pageKey != here.id && !walkToPage(edit.pageKey)) {
            Log.w(TAG, "a re-papered page could not be reached; the entry was dropped")
            return false
        }
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            return false
        }
        val target = if (undoing) edit.from else edit.to
        try {
            withContext(Dispatchers.IO) { s.setPageTemplate(edit.pageKey, target) }
        } catch (e: Exception) {
            showProblem(R.string.page_failed_title, R.string.page_failed_body)
            throw e
        }
        applyTemplate(edit.pageKey, target)
        return true
    }

    /** Walk back to the page [edit] was made on, bounded by the distance it recorded, flushing
     *  the page being left by copy alone as a turn does. */
    private suspend fun walkTo(edit: SketchEdit.RasterChanged): Boolean {
        val here = currentPage ?: return false
        val i = pages.indexOf(here)
        val target = pages.firstOrNull { it.id == edit.pageKey } ?: return false
        if (kotlin.math.abs(pages.indexOf(target) - i) > PageTurn.maxSteps(i, edit.pageIndex)) Log.w(TAG, "an edit's page sits further than it recorded; walking anyway")
        return walkToPage(edit.pageKey)
    }

    private suspend fun walkToPage(pageKey: String): Boolean {
        val target = pages.firstOrNull { it.id == pageKey } ?: return false
        if (!saver.flushForTurn()) Log.w(TAG, "the page's pixels could not be copied before the replay's turn; the raster stays dirty")
        loadPage(target, firstLoad = false)
        rememberLastOpened(target)
        return true
    }

    private val closeOpenEditRunnable = Runnable { closeOpenEdit() }

    /** Close the open contact's before-image into one history entry. Idempotent. */
    private fun closeOpenEdit() {
        paper.asView().removeCallbacks(closeOpenEditRunnable)
        val builder = openEdit ?: return
        openEdit = null
        if (builder.tooBig) {
            Log.w(TAG, "that contact covered more than the history can hold; it cannot be taken back")
            return
        }
        val edit = builder.build() ?: return
        undo.record(edit)
        Slog.d(TAG) {
            "undo entry: ${RasterRows.name(edit.layer)}, ${edit.tiles.size} tiles, ${edit.bytes} B, " +
                "read ${openEditReadNanos / 1_000_000} ms on the main thread (${undo.undoBytes} B held)"
        }
    }

    // ── The tools ────────────────────────────────────────────────────────────

    /** A tool choice the person made: on the engine, and on the device. A few small integers are
     *  not content, so the pick may be logged. */
    private fun pickTools(state: SketchToolState) {
        toolbar.apply(state)
        prefs.tools = state
        Slog.d(TAG) { "tools picked: $state" }
    }

    /** Arm a pen **kind** from the mini toolbar: the kind first, then the tool through the
     *  toolbar's own `arm`, which does the one pen-gated render release and the syncs. */
    private fun armPen(kind: SketchToolState.Kind) {
        pickTools(toolbar.state.withKind(kind))
        toolbar.arm(Tool.PEN)
    }

    /** Open the shade panel under [anchor], or close it — the armed pen button's re-tap toggle,
     *  from the top bar or from the mini toolbar (that row's own button). */
    private fun togglePaletteBar(anchor: View? = null) {
        val bar = paletteBar ?: return
        if (bar.isShowing) {
            hidePaletteBar()
            return
        }
        if (!opened || closing) return
        guides?.hide()   // one floating panel at a time
        val shown = if (anchor == null) bar.show() else bar.show(anchor)
        if (shown) pushExclusions()
    }

    /** Open the guides panel under [anchor], or close it — the Guides button's toggle, from the
     *  top bar or the collapsed overflow's own button. Opening it closes the shade panel. */
    private fun toggleGuidesBar(anchor: View) {
        val g = guides ?: return
        if (!g.isShowing) hidePaletteBar()
        g.toggle(anchor)
    }

    /** Idempotent; answers whether it was showing, so a caller inside [CollapsedChrome]'s close
     *  can leave the one exclusion push to it. */
    private fun takeDownPaletteBar(): Boolean {
        val bar = paletteBar ?: return false
        if (!bar.isShowing) return false
        bar.hide()
        return true
    }

    /** Idempotent — every dismiss path but the collapsed chrome's calls this one. */
    private fun hidePaletteBar() {
        if (takeDownPaletteBar()) pushExclusions()
    }

    /** The outside-contact dismissal — the eraser sub-bar's rule: any pointer landing anywhere
     *  but the panel itself, the armed pen button whose own re-tap toggles it, or the collapsed
     *  rows it may be hanging under, takes it down. Excluding the toggling button is load-bearing:
     *  a contact that both dismissed and re-opened the bar would make the toggle re-open what it
     *  meant to close, every time. */
    override fun dismissFloatingOnContact(ev: MotionEvent, index: Int) {
        val x = ev.getX(index).toInt()
        val y = ev.getY(index).toInt()
        if (floatingContains(x, y)) return
        paletteBar?.let { bar ->
            if (bar.isShowing) {
                val toggler = buttonOf(toolbar.state.kind)
                if (PaperToolbar.rectOf(toggler)?.contains(x, y) != true) hidePaletteBar()
            }
        }
        guides?.let { g ->
            // The Pick… button is inside the panel, so the picker's round trip leaves it open.
            if (g.isShowing && PaperToolbar.rectOf(binding.btnGuides)?.contains(x, y) != true) g.hide()
        }
    }

    /** The smudge rub's feed, before the base feeds the page gestures. A sequence qualifies at the
     *  down only: one finger (never a stylus — the pen has its own tools), on the page (not on
     *  chrome), with the pen gate open and the page truly on the glass. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        feedSmudge(ev, ev.actionMasked)
        return super.dispatchTouchEvent(ev)
    }

    private fun feedSmudge(ev: MotionEvent, action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val finger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                val allowed = finger && opened && !closing && !paper.isPenActive && !chrome.overChrome(ev) &&
                    !floatingContains(ev.x.toInt(), ev.y.toInt())
                if (allowed) paper.asView().getLocationInWindow(smudgeOrigin)
                smudge.down(ev.x - smudgeOrigin[0], ev.y - smudgeOrigin[1], ev.eventTime, allowed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> smudge.secondFinger()
            MotionEvent.ACTION_MOVE -> if (smudge.tracking) {
                val n = ev.historySize
                val points = ArrayList<StrokePoint>(n + 1)
                for (i in 0 until n) {
                    points.add(StrokePoint(
                        ev.getHistoricalX(0, i) - smudgeOrigin[0], ev.getHistoricalY(0, i) - smudgeOrigin[1],
                        pressure = SmudgeRub.PRESSURE, timeMillis = ev.getHistoricalEventTime(i),
                    ))
                }
                points.add(StrokePoint(
                    ev.getX(0) - smudgeOrigin[0], ev.getY(0) - smudgeOrigin[1],
                    pressure = SmudgeRub.PRESSURE, timeMillis = ev.eventTime,
                ))
                smudge.move(points)
            }
            MotionEvent.ACTION_UP -> smudge.up()
            MotionEvent.ACTION_CANCEL -> smudge.cancel()
        }
    }

    // ── g-paper → the page ───────────────────────────────────────────────────

    private val paperListener: PaperListener = object : PaperListener {

        /** **Deliberately ignored.** On a raster page the engine has already composited the mark
         *  into its style's page image and dropped the object by the time this fires. */
        override fun onStrokeCommitted(stroke: Stroke) = Unit

        /**
         * One of the page's two rasters is **about to** change — the one moment the pixels that
         * are there can still be read, and so the one moment an undo entry can be made of them.
         * The layered form is the one the engine calls; the rect is fed to the open contact's
         * builder, which reads each 64 px cell once. One contact is one raster; a second layer
         * inside one contact closes the entry on its own layer and opens a fresh one.
         */
        override fun onRasterWillChange(layer: RasterLayer, rect: Rect) {
            if (!opened || closing) return
            val page = currentPage ?: return
            val open = openEdit
            if (open != null && open.layer != layer) {
                Slog.d(TAG) { "one contact reported ${open.layer} then $layer; the first entry was closed" }
                closeOpenEdit()
            }
            val builder = openEdit
                ?.also { paper.asView().removeCallbacks(closeOpenEditRunnable) }
                ?: RasterEditBuilder(page.id, pages.indexOf(page), layer, page.width.toInt(), page.height.toInt()).also {
                    openEdit = it
                    openEditReadNanos = 0L
                }
            val t0 = System.nanoTime()
            builder.touch(rect.left, rect.top, rect.right, rect.bottom) { cell ->
                // The engine's array, straight into the tile — no copy. It goes back to the engine
                // as it stands, and the swap leaves it holding the other side of the edit.
                paper.readPageRaster(layer, Rect(cell.left, cell.top, cell.left + cell.width, cell.top + cell.height))?.pixels
            }
            openEditReadNanos += System.nanoTime() - t0
        }

        /** One of the page's rasters changed — a mark composited at pen-up, or one batch of a
         *  rubbing sweep. This is what arms the save, **for that raster alone**. */
        override fun onRasterChanged(layer: RasterLayer, rect: Rect) {
            if (!opened || closing) return
            saver.markDirty(layer)
            saver.schedule()
        }

        override fun onPenLifted() {
            lastPenLiftAt = SystemClock.uptimeMillis()
            saver.notePenLifted()   // a save past its deadline copies here, between strokes
            // A light rub with the stylus makes the tip switch chatter — four contacts a second —
            // and each was its own undo entry. Under the smudge the entry stays open a beat, and a
            // contact that lands inside it continues the same one.
            if (paper.tool == Tool.SMUDGE) {
                val v = paper.asView()
                v.removeCallbacks(closeOpenEditRunnable)
                v.postDelayed(closeOpenEditRunnable, SMUDGE_CHATTER_MS)
            } else {
                closeOpenEdit()
            }
        }

        override fun onToolChanged(tool: Tool) = toolbar.sync(tool)
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the sketchbook grows where you draw.
            val here = currentPage ?: return@runPageOp
            if (pages.indexOf(here) < pages.size - 1) turnPageNow(PageTurn.Direction.NEXT) else insertPageNow(after = true)
        }
        override fun onFlipPrevious() = runPageOp { turnPageNow(PageTurn.Direction.PREV) }
        override fun onInsertAfter() = runPageOp { insertPageNow(after = true) }
        override fun onInsertBefore() = runPageOp { insertPageNow(after = false) }
        override fun onUndo() = runPageOp { doReplay(undoing = true) }
        override fun onRedo() = runPageOp { doReplay(undoing = false) }
        // The long-press asks; it never acts.
        override fun onPageSheetRequested() = showPageSheet()
        // A finger double-tap hides / shows the chrome. Nothing on this surface answers a single
        // tap, so there is no collision rule here. The shade panel belongs to the chrome that is
        // flipping: it is hung off a button that is about to be `GONE`, or off rows that are.
        override fun onFingerDoubleTap(x: Float, y: Float) {
            hidePaletteBar()
            guides?.hide()
            toggleChrome()
        }
    }

    // ── Debug doors ──────────────────────────────────────────────────────────

    /**
     * **Debug only — walk aids, never in a release build.** adb cannot draw: the Supernote's
     * firmware ink is invisible to `screencap` and there is no gesture a shell can inject that puts
     * graphite on a raster page. Without these an agent-driven walk can prove that a save *runs*,
     * but never that a non-blank page round-trips.
     *
     * `am broadcast -a <pkg>.SKETCH_FILL` composites a fixed diagonal lattice of strokes with the
     * armed tool, as a bake does; `am broadcast -a <pkg>.SKETCH_DUMP` writes the page as the export
     * will see it — `renderToBitmap`, both rasters flattened in true grey over white, never the
     * panel's dither — to `files/dump/page.png` in this app's external files dir, for `adb pull`.
     * Registered only in debug builds, only while resumed.
     */
    private val debugDoors = if (!BuildConfig.DEBUG) null else object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Posted, never run inline: a receiver that bakes a page on Main holds the broadcast
            // past its timeout and Android kills the app (a dense fill is a minute's composite).
            val action = intent.action
            val dense = intent.getIntExtra("dense", 0)
            val noise = intent.getBooleanExtra("noise", false)
            // `--ei effort N`: the encoder's effort for every save from here on (−1 puts it back).
            if (intent.hasExtra("effort")) RasterImage.debugEffort = intent.getIntExtra("effort", -1).takeIf { it >= 0 }
            window.decorView.post {
                when (action) {
                    // `--ei dense N`: N passes of horizontal lines 2 px apart across the whole page,
                    // the worst case a shaded page can be — what the raster cap is measured against.
                    // `--ez noise true`: the graphite raster replaced by random colour, incompressible —
                    // the largest raster a page can produce (about 3 B/px), for the cap and the window.
                    "$packageName.SKETCH_FILL" -> runPageOp { if (noise) fillNoise() else fillTestPattern(dense = dense) }
                    "$packageName.SKETCH_DUMP" -> dumpPage()
                }
            }
        }
    }

    private fun fillTestPattern(dense: Int = 0) {
        val page = currentPage ?: return
        val tools = toolbar.state
        val w = page.width; val h = page.height
        val strokes = ArrayList<Stroke>(TEST_PATTERN_LINES)
        if (dense > 0) {
            // Edge to edge, 2 px apart, [dense] passes each a pixel lower: the whole page shaded.
            for (pass in 0 until dense) for (y in 0 until h.toInt() step 2) {
                val yy = (y.toFloat() + pass.toFloat() / dense).coerceAtMost(h - 1f)
                val points = ArrayList<StrokePoint>(TEST_PATTERN_POINTS)
                for (p in 0 until TEST_PATTERN_POINTS) points += StrokePoint(x = p / (TEST_PATTERN_POINTS - 1f) * (w - 1), y = yy, pressure = 0.7f)
                strokes += Stroke(id = "dense-$pass-$y-${System.nanoTime()}", points = points, color = tools.penColor, width = tools.penWidth, style = tools.penStyle)
            }
        } else for (i in 0 until TEST_PATTERN_LINES) {
            val t = (i + 1f) / (TEST_PATTERN_LINES + 1f)
            val points = ArrayList<StrokePoint>(TEST_PATTERN_POINTS)
            for (p in 0 until TEST_PATTERN_POINTS) {
                val u = p / (TEST_PATTERN_POINTS - 1f)
                points += StrokePoint(x = (w * 0.1f) + u * (w * 0.8f), y = (h * t * 0.8f) + (h * 0.1f) + u * (h * 0.08f), pressure = 0.5f)
            }
            strokes += Stroke(id = "test-$i-${System.nanoTime()}", points = points, color = tools.penColor, width = tools.penWidth, style = tools.penStyle)
        }
        closeOpenEdit()
        try {
            paper.addStrokes(strokes)
        } finally {
            closeOpenEdit()   // a composited bake produces no pen-up; one entry for the whole door
            toolbar.restorePen()
        }
        // A belt on the engine's own `onRasterChanged(layer, …)`, which has already marked these.
        for (layer in strokes.mapTo(LinkedHashSet()) { RasterLayer.of(it.style) }) saver.markDirty(layer)
        saver.schedule()
        Log.w(TAG, "debug fill door: ${strokes.size} test strokes composited")
    }

    private fun fillNoise() {
        val page = currentPage ?: return
        val w = page.width.toInt(); val h = page.height.toInt()
        val px = IntArray(w * h)
        val rnd = java.util.Random(7)
        for (i in px.indices) px[i] = (0xFF shl 24) or (rnd.nextInt() and 0xFFFFFF)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
        undo.clear()
        paper.loadPageRaster(RasterLayer.GRAPHITE, bmp)
        saver.markDirty(RasterLayer.GRAPHITE)
        saver.schedule()
        Log.w(TAG, "debug noise door: the graphite raster is ${w}×${h} of random colour")
    }

    private fun dumpPage() {
        val bmp = flattenShowingPage() ?: run { Log.w(TAG, "dump: nothing to render"); return }
        val dir = File(getExternalFilesDir(null), "dump").apply { mkdirs() }
        val f = File(dir, "page.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "dump: ${bmp.width}×${bmp.height} → ${f.absolutePath}")
    }

    // ── Page operations ──────────────────────────────────────────────────────

    /** Serialise every page / flush operation; ignore anything while not open or once closing. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "page op failed", t)
                }
            }
        }
    }

    /** A screen that opened nothing is explained, not toasted — then it leaves the way every exit does. */
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

    // ── The app in front ──────

    /** Whether the pen is down, hovering, or just lifted; the menu stays away while it is. */
    override fun penIsActive(): Boolean =
        opened && (paper.isPenActive || SystemClock.uptimeMillis() - lastPenLiftAt < PEN_RECENT_MS)

    /** Let the panel go for a frame: the side menu is about to be drawn over this screen. */
    override fun letPanelGo() {
        if (opened && !closing && !paper.isPenActive) paper.releaseRender()
    }

    /** Release the pipeline: a paper screen of Soil's is about to open over this one. */
    override fun letPipelineGo() {
        if (opened && !closing) paper.releaseForHandoff()
    }

    /** The bars reach Soil's shell from this window while it is in front. */
    override fun onBarKey(event: android.view.KeyEvent) = (application as SketchsproutApp).barKey(event)

    override fun onResume() {
        super.onResume()
        (application as SketchsproutApp).front(this)
        // The clipboard is the library's: something else may have copied to it while this
        // sketchbook was behind.
        refreshClipboard()
        // Back from Soil's picker: the pipeline first of all (the result callback reclaimed it).
        debugDoors?.let {
            val filter = IntentFilter("$packageName.SKETCH_FILL").apply { addAction("$packageName.SKETCH_DUMP") }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onPause() {
        debugDoors?.let { unregisterReceiver(it) }
        (application as SketchsproutApp).left(this)
        super.onPause()
    }

    /** A durability point while backgrounded, on the saver's own scope — ours is cancelled at
     *  ON_DESTROY, and a half-drawn page is the one thing worth surviving that. */
    override fun onScreenPaused() {
        if (!opened || closing) return
        saver.saveNow()
    }

    // ── Park and resume ──────

    override fun onStart() {
        super.onStart()
        val open = session ?: return
        runPageOp {
            withContext(Dispatchers.IO) { open.resume() }
            // Whatever a write refused while the session was parked is owed again now.
            saver.retryParked()
        }
    }

    override fun onStop() {
        super.onStop()
        val open = session ?: return
        // The cover on every way out but a hand-off to Soil's picker over this screen, the close
        // included: a sketchbook put down shows the library what it last showed.
        if (!inAppHandoff) captureCover()
        if (closing || inAppHandoff) return
        // The flush first, awaited — a parked session takes no write — then the park.
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock {
                    runCatching { saver.flushAndAwait() }.onFailure { Log.w(TAG, "the flush before the park failed: ${it.javaClass.simpleName}") }
                    withContext(Dispatchers.IO) { runCatching { open.park() }.onFailure { Log.w(TAG, "park failed: ${it.javaClass.simpleName}") } }
                }
            }
        }
    }

    /**
     * The library card's cover: the showing page as the engine has it — white, both rasters in
     * true grey — rendered here on Main, encoded and sent off it. Never while the pen is down, and
     * never worth failing a park for.
     */
    private fun captureCover() {
        if (!opened || paper.isPenActive) return
        val id = itemId ?: return
        val full = runCatching { flattenShowingPage() }.getOrNull() ?: return
        appScope.launch(Dispatchers.IO) {
            try {
                val bytes = CoverSnapshot.encode(full)
                (application as SketchsproutApp).soil.seam().setCover(id, SeamShared.write(bytes))
            } catch (e: Exception) {
                Log.w(TAG, "the cover was not written: ${e.javaClass.simpleName}")
            } finally {
                full.recycle()
            }
        }
    }

    /** The showing page as the export sees it: white, its paper, graphite, ink — composed here
     *  because the engine's own render never includes the sheet the paper rides on. Main thread. */
    private fun flattenShowingPage(): Bitmap? {
        val page = currentPage ?: return null
        val w = page.width.toInt(); val h = page.height.toInt()
        if (w <= 0 || h <= 0) return null
        val rasters = RasterRows.LAYERS.map { paper.getPageRaster(it) }
        return try {
            PageFlatten.flatten(w, h, paperCache[page.templateId], rasters)
        } finally {
            rasters.forEach { it?.recycle() }
        }
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    /**
     * Every exit — Back, the top bar's Back — **awaits the flush** and only then hands the pipeline
     * off and finishes. A flush that fails is a drawing with no other copy, so it is a **dialog**,
     * not a log line: Try again, or Leave anyway.
     */
    private fun exit() {
        if (closing) return
        closing = true
        dismissCollapsed()
        hidePaletteBar()
        guides?.hide()
        saver.cancelTimers()
        leaveWhenFlushed()
    }

    private fun leaveWhenFlushed() {
        appScope.launch {
            val ok = withContext(NonCancellable) { pageOps.withLock { saver.flushForExit() } }
            if (isFinishing || isDestroyed) return@launch
            if (ok) leaveNow() else askAboutUnsaved()
        }
    }

    /** The exit's last step: the door the exit opened, then the handoff and the finish. */
    private fun leaveNow() {
        afterExit?.invoke()
        afterExit = null
        finishWithHandoff()
    }

    private fun askAboutUnsaved() {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_not_saved_title)
                .setMessage(R.string.sketch_not_saved_body)
                .setPositiveButton(R.string.sketch_try_again) { _, _ -> leaveWhenFlushed() }
                .setNegativeButton(R.string.sketch_leave_anyway) { _, _ -> leaveNow() }
                .setCancelable(false)
                .create(),
        ).show()
    }

    override fun onScreenDestroyed() {
        if (::saver.isInitialized) saver.cancelTimers()
        val open = session ?: return
        session = null
        store = null
        appScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { open.close(true) }.onFailure { Log.w(TAG, "close failed: ${it.javaClass.simpleName}") }
        }
    }

    /** The title sits centred in the band the two button groups leave free, not on the screen —
     *  with three buttons on the start side the screen's centre lies under the title's start. The
     *  groups' laid-out widths become the title's margins. */
    private fun centreTitleInTheFreeBand() {
        val title = binding.title
        val lp = title.layoutParams as FrameLayout.LayoutParams
        val start = binding.toolGroup.width
        val end = binding.doorGroup.width
        if (lp.marginStart == start && lp.marginEnd == end && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER_VERTICAL
        lp.marginStart = start
        lp.marginEnd = end
        title.layoutParams = lp
    }

    private companion object {
        const val TAG = "SketchActivity"
        const val NO_SUCH_ITEM = "there is no such item"

        /** A page's paper is authored at the page's size; the bound only guards a foreign blob. */
        const val MAX_TEMPLATE_EDGE = 4096
        const val PAPER_CACHE_SIZE = 3

        /** How long after a pen lift the screen still counts as active for the menu. */
        const val PEN_RECENT_MS = 1_500L

        /** How long an undo entry stays open after a smudge contact lifts, so a chattering tip
         *  switch (~250 ms a contact on the Nomad) continues the entry rather than minting one. */
        const val SMUDGE_CHATTER_MS = 300L

        /** A hop of the smudge rub — the travel that fixes a direction. About 1 mm at 300 ppi. */
        const val SMUDGE_HOP_PX = 10f

        /** How far from its landing a finger may be at its first turn-back and still be rubbing
         *  rather than bouncing off a swipe: 2.4 cm at 300 ppi, well inside the swipe's own floor. */
        const val SMUDGE_ARM_WITHIN_PX = 280f

        /** The debug fill door's lattice — enough to be unmistakable in a `screencap`, few enough
         *  to composite in one call. */
        /** The marker's translucency as the palette's size samples draw it — g-paper's 45 %. */
        const val MARKER_SAMPLE_ALPHA = 115

        const val TEST_PATTERN_LINES = 12
        const val TEST_PATTERN_POINTS = 24

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
