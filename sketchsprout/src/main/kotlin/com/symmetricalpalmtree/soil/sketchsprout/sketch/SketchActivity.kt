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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.core.CoverSnapshot
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.PaperScreenActivity
import com.symmetricalpalmtree.soil.paper.ink.awaitPenIdle
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
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterRows
import com.symmetricalpalmtree.soil.sketchsprout.save.SketchSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
 * - **A page is two rasters, one picture**: a **graphite** image the pencil bakes into and the
 *   rubber rubs, and an **ink** image the gel pen (phase 2) bakes into and nothing erases. Neither
 *   is a user-facing layer: the artist sees the ink flattened over the graphite. Routing is
 *   g-paper's, by stroke style; this screen only ever *follows* the layer the engine names.
 * - **A mark is not an object.** `pageMode = RASTER` is set once, before any content: the engine
 *   composites each mark into its style's page image at pen-up and drops the stroke, so
 *   `onStrokeCommitted` is **deliberately ignored**.
 * - **Saves are [SketchSaver]'s**: debounced through the pen-idle gate up to a deadline, one
 *   raster at a time, each raster its own row; every leave flushes; a flush that fails is a
 *   dialog, because the pixels have no other copy.
 * - **Plain white paper in this phase**; the paper library's templates are laid under the raster
 *   in phase 4, the guides in phase 5, the undo in phase 3, the page sheet with it.
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

    /** Two tools on the mini toolbar: the pencil and the rubber are all this surface answers in
     *  this phase (the gel pen and Smudge join in phase 2). */
    override fun collapsedTools(): List<Tool> = listOf(Tool.PEN, Tool.ERASER)

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
        )

        toolbar = SketchToolbar(
            paper = paper,
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPencil = binding.btnPencil,
            btnEraser = binding.btnEraser,
            title = binding.title,
            pageIndicator = binding.pageIndicator,
            onBack = { exit() },
            onToolTapped = { dismissCollapsed() },
            onSynced = { syncCollapsed() },   // the corner button repaints with the bar
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
            standDown = { false },
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
                // Soil's New sketchbook may have chosen a paper. Phase 4 lays it; until then the
                // pick is consumed and logged, never left on the intent to be re-read.
                intent.getStringExtra(Seam.EXTRA_TEMPLATE_PICK)?.let {
                    intent.removeExtra(Seam.EXTRA_TEMPLATE_PICK)
                    Slog.d(TAG) { "a paper was picked; laid from phase 4" }
                }
                intent.removeExtra(Seam.EXTRA_PAGE_ID)   // landing on a page is phase 3's
                prefs.lastSketchbookId = item.id
                loaded to item.name
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
        val first = pages.firstOrNull { it.id == loadedBook.first.currentId } ?: pages.first()
        loadPage(first, firstLoad = true)
        if (isFinishing || isDestroyed || closing) return
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
        if (saver.isPushPending(page.id)) {
            val t0 = SystemClock.elapsedRealtime()
            saver.awaitPushes(page.id)
            Slog.d(TAG) { "waited ${SystemClock.elapsedRealtime() - t0} ms for this page's own save before loading it" }
        }
        dismissCollapsed()   // a floating row never survives a content swap
        if (!firstLoad) paper.clearForContentSwap()
        val width = page.width.toInt(); val height = page.height.toInt()
        paper.setPageSize(width, height)
        paper.setTemplate(null)   // plain white in this phase; the paper lands in the sheet from phase 4
        if (isFinishing || isDestroyed) return
        val s = store ?: return
        val decoded = ArrayList<Pair<RasterLayer, Bitmap?>>(RasterRows.LAYERS.size)
        for (layer in RasterRows.LAYERS) {
            val bitmap = withContext(Dispatchers.IO) {
                val bytes = runCatching { s.readRaster(page.id, layer) }
                    .onFailure { Log.w(TAG, "the page's ${RasterRows.name(layer)} raster could not be read: ${it.javaClass.simpleName}") }
                    .getOrNull()
                RasterImage.decode(bytes, width, height)
            }
            if (isFinishing || isDestroyed) { bitmap?.recycle(); decoded.forEach { it.second?.recycle() }; return }
            decoded += layer to bitmap
        }
        for ((layer, bitmap) in decoded) {
            // Silent: a page we loaded ourselves is our own news, so no will-change/changed pair
            // arrives and nothing here has to swallow one.
            paper.loadPageRaster(layer, bitmap)
            bitmap?.recycle()
        }
        currentPage = page
        saver.pageKey = page.id
        saver.markClean()
        toolbar.setPage(pages.indexOf(page) + 1, pages.size)
    }

    // ── g-paper → the page ───────────────────────────────────────────────────

    private val paperListener: PaperListener = object : PaperListener {

        /** **Deliberately ignored.** On a raster page the engine has already composited the mark
         *  into its style's page image and dropped the object by the time this fires. */
        override fun onStrokeCommitted(stroke: Stroke) = Unit

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
        }

        override fun onToolChanged(tool: Tool) = toolbar.sync(tool)
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        // A finger double-tap hides / shows the chrome. Nothing on this surface answers a single
        // tap, so there is no collision rule here. Turns, inserts, undo and the page sheet are
        // phase 3's.
        override fun onFingerDoubleTap(x: Float, y: Float) = toggleChrome()
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
            when (intent.action) {
                "$packageName.SKETCH_FILL" -> runPageOp { fillTestPattern() }
                "$packageName.SKETCH_DUMP" -> dumpPage()
            }
        }
    }

    private fun fillTestPattern() {
        val page = currentPage ?: return
        val tools = toolbar.state
        val w = page.width; val h = page.height
        val strokes = ArrayList<Stroke>(TEST_PATTERN_LINES)
        for (i in 0 until TEST_PATTERN_LINES) {
            val t = (i + 1f) / (TEST_PATTERN_LINES + 1f)
            val points = ArrayList<StrokePoint>(TEST_PATTERN_POINTS)
            for (p in 0 until TEST_PATTERN_POINTS) {
                val u = p / (TEST_PATTERN_POINTS - 1f)
                points += StrokePoint(x = (w * 0.1f) + u * (w * 0.8f), y = (h * t * 0.8f) + (h * 0.1f) + u * (h * 0.08f), pressure = 0.5f)
            }
            strokes += Stroke(id = "test-$i-${System.nanoTime()}", points = points, color = tools.penColor, width = tools.penWidth, style = tools.penStyle)
        }
        try {
            paper.addStrokes(strokes)
        } finally {
            toolbar.restorePen()
        }
        // A belt on the engine's own `onRasterChanged(layer, …)`, which has already marked these.
        for (layer in strokes.mapTo(LinkedHashSet()) { RasterLayer.of(it.style) }) saver.markDirty(layer)
        saver.schedule()
        Log.w(TAG, "debug fill door: ${strokes.size} test strokes composited")
    }

    private fun dumpPage() {
        val bmp = paper.renderToBitmap() ?: run { Log.w(TAG, "dump: nothing to render"); return }
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
        // The cover on every way out, the close included: a sketchbook put down shows the library
        // what it last showed.
        captureCover()
        if (closing) return
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
        val full = runCatching { paper.renderToBitmap() }.getOrNull() ?: return
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
        saver.cancelTimers()
        leaveWhenFlushed()
    }

    private fun leaveWhenFlushed() {
        appScope.launch {
            val ok = withContext(NonCancellable) { pageOps.withLock { saver.flushForExit() } }
            if (isFinishing || isDestroyed) return@launch
            if (ok) finishWithHandoff() else askAboutUnsaved()
        }
    }

    private fun askAboutUnsaved() {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_not_saved_title)
                .setMessage(R.string.sketch_not_saved_body)
                .setPositiveButton(R.string.sketch_try_again) { _, _ -> leaveWhenFlushed() }
                .setNegativeButton(R.string.sketch_leave_anyway) { _, _ -> finishWithHandoff() }
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

        /** How long after a pen lift the screen still counts as active for the menu. */
        const val PEN_RECENT_MS = 1_500L

        /** The debug fill door's lattice — enough to be unmistakable in a `screencap`, few enough
         *  to composite in one call. */
        const val TEST_PATTERN_LINES = 12
        const val TEST_PATTERN_POINTS = 24

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
