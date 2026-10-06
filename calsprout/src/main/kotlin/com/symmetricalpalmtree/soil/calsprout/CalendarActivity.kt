package com.symmetricalpalmtree.soil.calsprout

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.soil.calsprout.databinding.ActivityCalendarBinding
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedChrome
import com.symmetricalpalmtree.soil.paper.chrome.DayPickerDialog
import com.symmetricalpalmtree.soil.paper.chrome.EraserBar
import com.symmetricalpalmtree.soil.paper.chrome.InkSelectionBar
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.InkScreenActivity
import com.symmetricalpalmtree.soil.seam.Seam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import java.time.LocalTime
import kotlin.coroutines.resume

/**
 * **The calendar** — Notesprout SN's organizer as a Sprout app's one screen: Month, Week and Day
 * pages, each a writing surface, on `:paper`'s [InkScreenActivity] exactly as the Scratch Pad is:
 * **the whole skeleton is there** — full-bleed g-paper, the page-op lock, the undo/redo replay
 * (with this screen's [followReplay] hook), the debounced save against every leave flush, the
 * chrome band and exclusions, the collapsed corner chrome, the EPD handoff. This class is what is
 * the **calendar's own**: navigation, the template bake and the double-tap. The page and its
 * persistence are [CalendarDocument]'s; the store is Soil's, lent over the seam and held once per
 * process ([CalsproutApp.calendar]) — **the app writes nothing to disk itself**.
 *
 * **The grid is the page's template.** [CalendarGeometry] lays it out at the page's own size, the
 * full page with the bars floating over it, [CalendarTemplate] paints it, and g-paper sets it
 * behind the ink — so a store carried to another screen keeps grid and ink registered. It is
 * re-baked on every navigation and on `onResume`, because today's ring moves. The page rect is
 * anchored top-left and the page *is* the whole surface, so **a finger's view coordinates are
 * page coordinates 1:1** — the double-tap hit-tests the raw point against the same geometry the
 * template was painted from.
 *
 * **Navigation is [CalendarNavigation]'s, and every route ends in [showMove].** The pager steps
 * the period (buttons, or a finger swipe); Today lands on today in the showing view; the three
 * latches change the view; a finger double-tap on a Month or Week cell opens that day's Day page.
 * The screen holds no navigation rule of its own.
 *
 * **Opened on a day** ([Seam.EXTRA_CAL_DATE] — a link followed from a notebook or a document):
 * that day's Day page, ahead of the bookmark, as a pick. Otherwise the bookmark, or today's Month.
 *
 * Every navigation writes the bookmark and nothing else — **rows are minted on the first stroke,
 * never on open**, so browsing an empty year leaves the store exactly as it was.
 *
 * Undo is **calendar-level, in memory, per showing**: an action names its page, and replaying one
 * recorded on another page navigates there first ([CalendarDocument.revert]).
 *
 * Frame silence: no app frame while `paper.isPenActive`. The title waits for the gate
 * ([CalendarToolbar]); the frames that do not are the recorded exceptions — the selection bar's
 * show at lasso completion, the "Opening…" box's hide when the page lands, a problem dialog at a
 * chrome tap, and the chrome flip at a finger double-tap.
 */
class CalendarActivity : InkScreenActivity<InkAction>(), CalsproutApp.FrontPaper {

    private lateinit var binding: ActivityCalendarBinding
    private lateinit var toolbar: CalendarToolbar
    private lateinit var palette: CalendarTemplate.Palette
    private lateinit var prefs: CalsproutPrefs
    private var document: CalendarDocument? = null

    /** Where the organizer is looking and what each control does to it — the anchor rule, pure. */
    private val nav = CalendarNavigation()

    /**
     * The Events screen, launched for a result. Registered as a property, because a launcher must
     * be registered before the Activity is STARTED. On the way back the screen names the day it
     * ended on, and the calendar follows it in the view it is in (Greg's "Return" decision, SN). A
     * result that names no day (a crash, a kill) moves nothing.
     */
    private val eventsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ended = result.data?.getStringExtra(EventsActivity.EXTRA_ENDED_ON)?.let(CalendarDates::parse)
            if (ended == null || !opened || closing || isFinishing || isDestroyed) return@registerForActivityResult
            runPageOp { showMove(nav.picked(ended, LocalDate.now(), nowHour())) }
        }

    /** The day Soil asked for, or null for the bookmark. Read once, at create. */
    private var openOn: LocalDate? = null

    /** What the template on the paper was baked from — the page, the day and the page size. A
     *  [showPage] whose key is unchanged (an undo or redo on the showing page) reloads the strokes
     *  and nothing else: no page-sized bitmap, no extra EPD frames. */
    private var bakeKey: BakeKey? = null
    private var baked: android.graphics.Bitmap? = null

    private data class BakeKey(
        val target: CalendarTarget,
        val today: LocalDate,
        val width: Int,
        val height: Int,
    )

    // ── What the skeleton asks for ───────────────────────────────────────────

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
    override val storeFailedTitleRes: Int get() = R.string.calendar_store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.calendar_store_failed_body

    /** The calendar opens with its bars as they were left. */
    override val initialChromeHidden: Boolean get() = prefs.chromeHidden

    override fun onChromeChanged(hidden: Boolean) { prefs.chromeHidden = hidden }

    /** The calendar has no page-level action, so its stack is `:paper`'s four kinds unwrapped. */
    override fun record(action: InkAction) = undo.record(action)

    override fun syncTool(tool: Tool) = toolbar.sync(tool)

    override fun armTool(tool: Tool) = toolbar.arm(tool)

    /**
     * Back, then every door on the top bar in bar order. No pager — a swipe still steps the
     * period while the chrome is collapsed. Every entry **mirrors** its bar button: the armed
     * view's latch and a door's absence are read off the bar at each open, and a tap performs
     * the bar button's own click.
     */
    override fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(
        backEntry(),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_month, binding.btnMonth),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_week, binding.btnWeek),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_day, binding.btnDay),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_star, binding.btnToday),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_calendar_event, binding.btnEvents),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_link, binding.btnLinks),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_dots, binding.btnMore),
    )

    override fun showPage() = showPage(firstLoad = false)

    override suspend fun revert(action: InkAction) {
        document?.revert(action)
    }

    override suspend fun reapply(action: InkAction) {
        document?.reapply(action)
    }

    /** A replay may have navigated the document to the action's page; the organizer follows, or
     *  the latches, the pager and a double-tap would all act on the page it still believed was
     *  showing. */
    override fun followReplay() {
        val doc = document ?: return
        nav.landed(doc.target, LocalDate.now(), nowHour())?.let { nav.shown(it) }
    }

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CalsproutPrefs(this)
        // Soil's hand: a day to open on, or nothing. An unreadable one is nothing.
        openOn = intent.getStringExtra(Seam.EXTRA_CAL_DATE)?.let(CalendarDates::parse)
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge
        palette = CalendarTemplate.Palette(
            ink = ContextCompat.getColor(this, com.symmetricalpalmtree.soil.paper.R.color.inkBlack),
            light = ContextCompat.getColor(this, com.symmetricalpalmtree.soil.paper.R.color.inkLight),
        )

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        // Both pen-gesture recognisers on, and armed BEFORE the listener attaches. They match the
        // notebook deliberately: a calendar that lassoed differently would read as a bug.
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        // The page goes direct to the Supernote panel, as the pad's and the notebook's do: the
        // grid and the strokes are the flatten base, the live pen, the point eraser and the
        // lasso's trail are painted by the app. Every panel post is cut around the exclusion rects.
        paper.directInk = true
        paper.setPaperListener(paperListener)

        toolbar = CalendarToolbar(
            paper = paper,
            onSynced = { syncCollapsed() },   // the corner button repaints with the bar
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnMonth = binding.btnMonth,
            btnWeek = binding.btnWeek,
            btnDay = binding.btnDay,
            btnToday = binding.btnToday,
            btnEvents = binding.btnEvents,
            btnLinks = binding.btnLinks,
            btnMore = binding.btnMore,
            btnPrev = binding.btnPrev,
            btnNext = binding.btnNext,
            title = binding.title,
            onBack = { exit() },
            onView = { kind -> runPageOp { nav.toggled(kind)?.let { showMove(it) } } },
            onToday = { runPageOp { showMove(nav.todayMove(LocalDate.now(), nowHour())) } },
            onEvents = { openEvents() },
            onLinks = null,    // phase 9
            onMore = null,     // phase 6
            onPrev = { runPageOp { step(forward = false) } },
            onNext = { runPageOp { step(forward = true) } },
            onTitle = { showPicker() },
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
            // Copy of the lasso's strokes comes with phase 6.
            sendHint = null,
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            // The surface accepts no ink until the page is truly on it.
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
        // Both bars hide and show together on a finger double-tap in the band or on a Day page,
        // opening as they were left. The grid is full page either way — hiding the bars only
        // uncovers what is already drawn there.
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        lifecycleScope.launch { openDocument() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openDocument() {
        val doc: CalendarDocument
        try {
            // The surface's real size is what the grid is laid out on — wait for the first layout
            // rather than guessing from the display metrics. The root only: a launch with the
            // chrome hidden has GONE bars that never get a height.
            binding.root.awaitLaidOut()
            // The first open of all mints the store, which derives a key: seconds, under the
            // "Opening…" box. A store Soil will not lend throws here.
            val store = (application as CalsproutApp).calendar()
            doc = CalendarDocument(store) { surfaceSize() }
            document = doc
            val bookmark = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val counts = runCatching { store.counts() }.getOrNull()
                Slog.d(TAG) { "rows: ${counts?.periods} period(s), ${counts?.pages} page(s), ${counts?.strokes} stroke(s), ${counts?.events} event(s)" }
                store.readBookmark()
            }
            val today = LocalDate.now()
            // A day Soil asked for opens as a pick of that day's Day page, ahead of the bookmark:
            // the day is the reason this screen is up. Otherwise the bookmark is honoured
            // whatever kind it names, and an unreadable one is the first-run answer, today's Month.
            val asked = openOn
            val move = if (asked != null) {
                nav.shown(nav.opening(CalendarTarget.of(CalendarTarget.KIND_DAY, asked), today, nowHour()))
                nav.picked(asked, today, nowHour())
            } else {
                nav.opening(bookmark?.target, today, nowHour())
            }
            doc.show(move.target)
            nav.shown(move)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never the message: an open's message can carry a path.
            Log.w(TAG, "the calendar's store could not be opened: ${e.javaClass.simpleName}")
            document = null
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        showPage(firstLoad = true)
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // Deliberately NOT pen-idle-gated: a boundary frame, nothing has been drawn yet.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page ${doc.target.kind}/${doc.target.date}/${doc.target.half} loaded: ${doc.strokes.size} strokes" }
    }

    private suspend fun View.awaitLaidOut() {
        if (width > 0 && height > 0) return
        suspendCancellableCoroutine { cont ->
            val l = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            addOnLayoutChangeListener(l)
            cont.invokeOnCancellation { removeOnLayoutChangeListener(l) }
        }
    }

    // ── Page gestures → operations ───────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp { step(forward = true) }
        override fun onFlipPrevious() = runPageOp { step(forward = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        // A double-tap routes by zone: a day cell opens that day, the Notes band and a Day page
        // toggle the chrome. `onFingerTap` is deliberately NOT overridden: a single tap selects
        // nothing here. No long-press, no inserts, no swipe-down either.
        override fun onFingerDoubleTap(x: Float, y: Float) = runPageOp { doubleTap(x, y) }
    }

    /**
     * The one road every navigation takes: put the move's page on the paper, then record that it
     * landed. The order matters — [CalendarNavigation.shown] is what moves the anchor, and a show
     * that threw (a store gone out from under us) must leave the organizer exactly where it was.
     */
    private suspend fun showMove(m: CalendarNavigation.Move, firstLoad: Boolean = false) {
        val doc = document ?: return
        doc.show(m.target)
        nav.shown(m)
        showPage(firstLoad)
    }

    /** One period forward or back in the showing view — the pager's buttons and the finger swipe. */
    private suspend fun step(forward: Boolean) = showMove(nav.stepped(forward, LocalDate.now(), nowHour()))

    /**
     * A finger double-tap, routed by zone: a Month/Week cell → that day as a Day page; the Notes
     * band → the chrome toggle; a Day page → the toggle anywhere; the header, the side margins, a
     * hairline, the spare Week cell → nothing, silently. Page coordinates are view coordinates
     * 1:1. Inside `runPageOp` so it is serialised against a page swap.
     */
    private suspend fun doubleTap(x: Float, y: Float) {
        val t = document?.target ?: return
        val decision = CalendarDoubleTap.decide(
            kind = t.kind, x = x, y = y, date = t.localDate,
            month = if (t.kind == CalendarTarget.KIND_MONTH) monthGeometry() else null,
            week = if (t.kind == CalendarTarget.KIND_WEEK) weekGeometry() else null,
        )
        when (decision) {
            is CalendarDoubleTap.Decision.OpenDay -> nav.dayAt(decision.date)?.let { showMove(it) }
            CalendarDoubleTap.Decision.Toggle -> toggleChrome()
            CalendarDoubleTap.Decision.Nothing -> Unit
        }
    }

    /**
     * The Events door. The day it opens on is the **first day of the period showing** — the 1st of
     * a month, a week's Sunday, or the day itself ([EventsLaunch], Greg's decision in SN).
     */
    private fun openEvents() {
        if (!opened || closing || isFinishing || isDestroyed) return
        val day = EventsLaunch.launchDay(nav.kind, nav.target.localDate)
        Slog.d(TAG) { "events: opening $day from kind ${nav.kind}" }
        eventsLauncher.launch(
            Intent(this, EventsActivity::class.java).putExtra(EventsActivity.EXTRA_DAY, CalendarDates.format(day)),
        )
    }

    /** The pager title's day picker. A dialog raised at a chrome tap — the recorded exception,
     *  not a new one — and the pick itself is a page op like every other navigation. */
    private fun showPicker() {
        if (!opened || closing || isFinishing || isDestroyed) return
        DayPickerDialog.show(this, nav.anchor) { day ->
            runPageOp { showMove(nav.picked(day, LocalDate.now(), nowHour())) }
        }
    }

    /** The hour the clock says, for the half a Day page opens on. */
    private fun nowHour(): Int = LocalTime.now().hour

    /**
     * Put the document's page on the paper. The order is g-paper's page-swap law:
     * `clearForContentSwap` (pixels hold — no blank flash on e-ink) → `setPageSize` /
     * `setTemplate` → `loadStrokes`, which is a single EPD refresh. Any selection goes first,
     * because a data-in call would dismiss it anyway and it belongs to the page being left.
     */
    private fun showPage(firstLoad: Boolean) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()
        hideEraserBar()      // a floating bar never survives a content swap
        dismissCollapsed()   // and neither do the corner button's rows
        if (!firstLoad) paper.clearForContentSwap()
        applyTemplate()
        paper.loadStrokes(doc.strokes)
        toolbar.setTitle(titleOf(doc.target))
        // The latch says what is on the paper. It rides this frame; it is never one of its own.
        toolbar.setView(doc.target.kind)
    }

    /**
     * Put the showing page's size and template on the paper — baked only when something the bake
     * depends on has changed ([BakeKey]). `setPageSize` and `setTemplate` are each an EPD repaint,
     * and a bake is a page-sized bitmap rasterized with forty-odd labelled cells: an undo that
     * changes neither pays for neither. The replaced bitmap is recycled — g-paper holds only the
     * one it was last given.
     */
    private fun applyTemplate() {
        val doc = document ?: return
        val key = BakeKey(doc.target, LocalDate.now(), doc.pageWidth.toInt(), doc.pageHeight.toInt())
        if (key == bakeKey && baked != null) return
        val fresh = bakeTemplate(doc.target)
        val old = baked
        bakeKey = key
        baked = fresh
        paper.setPageSize(key.width, key.height)
        paper.setTemplate(fresh)
        old?.recycle()
    }

    /** The page's grid at the page's own size, full page — the three layouts dispatched by the
     *  showing page's kind. */
    private fun bakeTemplate(t: CalendarTarget): android.graphics.Bitmap {
        val today = LocalDate.now()
        val density = resources.displayMetrics.density
        val notes = getString(R.string.calendar_notes_label)
        return when (t.kind) {
            CalendarTarget.KIND_WEEK -> CalendarTemplate.week(weekGeometry(), t.localDate, today, density, palette, notes)
            CalendarTarget.KIND_DAY -> CalendarTemplate.day(dayGeometry(), t.half, density, palette)
            else -> CalendarTemplate.month(monthGeometry(), t.localDate, today, density, palette, notes)
        }
    }

    private fun monthGeometry() = CalendarGeometry.month(pageWidthPx(), pageHeightPx(), resources.displayMetrics.density)
    private fun weekGeometry() = CalendarGeometry.week(pageWidthPx(), pageHeightPx(), resources.displayMetrics.density)
    private fun dayGeometry() = CalendarGeometry.day(pageWidthPx(), pageHeightPx(), resources.displayMetrics.density)

    private fun pageWidthPx(): Int = (document?.pageWidth ?: 0f).toInt()
    private fun pageHeightPx(): Int = (document?.pageHeight ?: 0f).toInt()

    private fun titleOf(t: CalendarTarget): String = when (t.kind) {
        CalendarTarget.KIND_MONTH -> CalendarDates.monthTitle(t.localDate)
        CalendarTarget.KIND_WEEK -> CalendarDates.weekTitle(t.localDate)
        else -> CalendarDates.dayTitle(t.localDate, t.half)
    }

    // ── The app in front ──────

    /** Whether the pen is down, hovering, or just lifted; the menu stays away while it is. */
    override fun penIsActive(): Boolean = opened && penRecentlyActive()

    /** Let the panel go for a frame: the side menu is about to be drawn over this screen. */
    override fun letPanelGo() {
        if (opened && !closing && !paper.isPenActive) paper.releaseRender()
    }

    /** Release the pipeline: a paper screen of Soil's is about to open over this one. */
    override fun letPipelineGo() {
        if (opened && !closing) paper.releaseForHandoff()
    }

    /** The bars reach Soil's shell from this window while it is in front (its own filter is off over paper). */
    override fun onBarKey(event: android.view.KeyEvent) = (application as CalsproutApp).barKey(event)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        (application as CalsproutApp).front(this)
        // A date rolled over while the screen sat in the background: the ring moves with it. Only
        // when it did — a resume is otherwise not a frame. The day the showing template was baked
        // for is part of the bake key, so a changed `today` is a changed key, and a changed key
        // is a bake.
        val key = bakeKey ?: return
        if (opened && !closing && key.today != LocalDate.now()) applyTemplate()
    }

    override fun onPause() {
        (application as CalsproutApp).left(this)
        super.onPause()
    }

    private companion object {
        const val TAG = "CalendarActivity"
    }
}
