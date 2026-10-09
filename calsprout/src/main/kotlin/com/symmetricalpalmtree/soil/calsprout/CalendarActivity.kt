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
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.soil.calsprout.databinding.ActivityCalendarBinding
import com.symmetricalpalmtree.soil.paper.chrome.BacklinksModel
import com.symmetricalpalmtree.soil.paper.chrome.BacklinksPanel
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedChrome
import com.symmetricalpalmtree.soil.paper.chrome.DayPickerDialog
import com.symmetricalpalmtree.soil.paper.chrome.EraserBar
import com.symmetricalpalmtree.soil.paper.chrome.InkSelectionBar
import com.symmetricalpalmtree.soil.paper.chrome.LassoPopup
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.InkPlacement
import com.symmetricalpalmtree.soil.paper.ink.InkScreenActivity
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.paper.templates.BuiltInTemplates
import com.symmetricalpalmtree.soil.seam.SeamCalBacklink
import com.symmetricalpalmtree.soil.seam.SeamClip
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import com.symmetricalpalmtree.soil.seam.Seam
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Every navigation writes the bookmark (not on a screen a link opened: that leaves it untouched)
 * and nothing else — **rows are minted on the first stroke,
 * never on open**, so browsing an empty year leaves the store exactly as it was.
 *
 * **Ink across is the clipboard** (Greg, 2026-10-05: copy and paste, never Send), and it is the
 * notebook's shape exactly (Greg, 2026-10-06): **strokes are the lasso's, pages are the page
 * sheet's.** Copy on the selection bar puts the lasso's strokes on the notebook slot as the pad
 * does ([InkClip.envelopeOf]); while the lasso is armed and ink is on the clipboard, **a stylus
 * tap on bare paper pastes it centred on the tap** (the notebook's tap-to-place), selected; the
 * [LassoPopup] under a re-tap holds Paste at the source coordinates and Clear. A finger long-press
 * raises the page sheet: Copy page writes the page — a Day both halves — as a notebook page clip
 * papered with the grid ([InkClip.pageEnvelopeOf]), which the notebook's page sheet pastes before
 * or after; Paste page lands a copied page's ink on the showing page at its own coordinates;
 * Export… opens Soil's export screen on the period showing ([RenderKey], [RenderService]).
 * **Links** lists what in the library links into the period showing ([CalLinksModel]) in the
 * shared backlinks panel, and is on the bar only while something does. The
 * slot's header is read again every time this screen comes to the front, so each Paste is offered
 * exactly when its kind of clip is there — absent, never disabled — and the lasso wears the
 * clipboard mark while ink is.
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
            // Force the bake: an event may have been added or deleted, and the grid's marks are
            // baked into the template.
            runPageOp { showMove(nav.returned(ended, LocalDate.now(), nowHour()), forceBake = true) }
        }

    /** The day Soil asked for, or null for the bookmark. Read once, at create. */
    private var openOn: LocalDate? = null

    private lateinit var lassoPopup: LassoPopup

    /** The Links panel while it is up; one showing and one gather at a time. */
    private var linksPanel: BacklinksPanel<CalLinksModel.Group>? = null
    private var gatheringLinks = false

    /** The page the Links door was last answered for: a stale answer is not applied. */
    private var linksAskedFor: CalendarTarget? = null

    /** What the notebook slot holds — [ClipEnvelope.KIND_OBJECTS], [ClipEnvelope.KIND_PAGE] or
     *  null — read at every resume and set by every copy. Each Paste is offered on its own kind. */
    @Volatile
    private var clipKind: String? = null
        set(value) {
            field = value
            markClipboard(value == ClipEnvelope.KIND_OBJECTS)
        }

    /** What the template on the paper was baked from — the page, the day and the page size. A
     *  [showPage] whose key is unchanged (an undo or redo on the showing page) reloads the strokes
     *  and nothing else: no page-sized bitmap, no extra EPD frames. */
    private var bakeKey: BakeKey? = null
    private var baked: android.graphics.Bitmap? = null

    /**
     * [marks] is compared **structurally**, not by a hash: a collision here is a page that silently
     * keeps showing an event the person just deleted. A `Map` of a handful of small data classes
     * costs nothing to compare against the page-sized bitmap it decides.
     */
    private data class BakeKey(
        val target: CalendarTarget,
        val today: LocalDate,
        val width: Int,
        val height: Int,
        val marks: Map<LocalDate, List<DayMark>>,
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
        CollapsedChrome.Entry.mirroring(com.symmetricalpalmtree.soil.paper.R.drawable.ic_notebook, binding.btnLinks),
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
            btnPrev = binding.btnPrev,
            btnNext = binding.btnNext,
            title = binding.title,
            onBack = { exit() },
            onView = { kind -> runPageOp { nav.toggled(kind)?.let { showMove(it) } } },
            onToday = { runPageOp { showMove(nav.todayMove(LocalDate.now(), nowHour())) } },
            onEvents = { openEvents() },
            onLinks = { openLinks() },
            onPrev = { runPageOp { step(forward = false) } },
            onNext = { runPageOp { step(forward = true) } },
            onTitle = { showPicker() },
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
            copyHint = getString(R.string.calendar_copy_selection),
            onCopy = { currentSelection?.let { copySelection(it) } },
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
            val app = application as CalsproutApp
            val store = app.calendar()
            // The marks follow the store: a lease Soil let go is opened again by the document's
            // re-acquire, and the events half comes with it.
            var events = app.events()
            doc = CalendarDocument(
                store,
                marks = MarkSource { from, to -> events.marksFor(from, to) },
                // A link's day opens "the bookmark untouched" (docs/calsprout.md).
                writesBookmark = openOn == null,
                reacquire = { app.calendar().also { events = app.events() } },
            ) { surfaceSize() }
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
        // Counts only — never a title: an event's words are the person's own.
        Slog.d(TAG) { "page ${doc.target.kind}/${doc.target.date}/${doc.target.half} loaded: ${doc.strokes.size} strokes, ${doc.marks.size} marked day(s)" }
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
        // nothing here. No inserts, no swipe-down.
        override fun onFingerDoubleTap(x: Float, y: Float) = runPageOp { doubleTap(x, y) }
        // A finger long-press asks what can be done with this page — the notebook's gesture.
        override fun onPageSheetRequested() = showPageSheet()
    }

    /**
     * The one road every navigation takes: put the move's page on the paper, then record that it
     * landed. The order matters — [CalendarNavigation.shown] is what moves the anchor, and a show
     * that threw (a store gone out from under us) must leave the organizer exactly where it was.
     */
    private suspend fun showMove(m: CalendarNavigation.Move, firstLoad: Boolean = false, forceBake: Boolean = false) {
        val doc = document ?: return
        // [forceBake] is only ever set by the events screen's return, and that is exactly the case
        // where the page may not have moved while its marks did: ask for them again.
        doc.show(m.target, refreshMarks = forceBake)
        nav.shown(m)
        showPage(firstLoad, forceBake)
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
    private fun showPage(firstLoad: Boolean, forceBake: Boolean = false) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()
        hideEraserBar()      // a floating bar never survives a content swap
        dismissCollapsed()   // and neither do the corner button's rows
        if (!firstLoad) paper.clearForContentSwap()
        applyTemplate(force = forceBake)
        paper.loadStrokes(doc.strokes)
        toolbar.setTitle(titleOf(doc.target))
        // The latch says what is on the paper. It rides this frame; it is never one of its own.
        toolbar.setView(doc.target.kind)
        refreshLinksDoor(doc.target)
    }

    // ── Links ────────────────────────────────────────────────────────────────

    /** Whether anything links into [t]: asked of Soil's index on IO, the door shown on the
     *  answer — for the page still showing, behind the pen-idle gate like the title. */
    private fun refreshLinksDoor(t: CalendarTarget) {
        linksAskedFor = t
        lifecycleScope.launch {
            val any = withContext(Dispatchers.IO) {
                runCatching {
                    val (from, to) = CalLinksModel.scopeOf(t)
                    (application as CalsproutApp).soil.seam().calBacklinks(CalendarDates.format(from), CalendarDates.format(to)).isNotEmpty()
                }.getOrDefault(false)
            }
            if (linksAskedFor != t || closing || isFinishing || isDestroyed) return@launch
            toolbar.showLinks(any)
            syncCollapsed()
        }
    }

    /** The Links panel: everything in the library that links into the period showing. */
    private fun openLinks() {
        if (!opened || closing || linksPanel != null || gatheringLinks) return
        val t = document?.target ?: return
        gatheringLinks = true
        lifecycleScope.launch {
            val rows: List<SeamCalBacklink> = withContext(Dispatchers.IO) {
                runCatching {
                    val (from, to) = CalLinksModel.scopeOf(t)
                    (application as CalsproutApp).soil.seam().calBacklinks(CalendarDates.format(from), CalendarDates.format(to))
                }.getOrElse { e ->
                    Log.w(TAG, "the links could not be read: ${e.javaClass.simpleName}")
                    emptyList()
                }
            }
            gatheringLinks = false
            if (linksPanel != null || isFinishing || isDestroyed || closing) return@launch
            val groups = CalLinksModel.group(rows)
            Slog.d(TAG) { "links: ${groups.size} entr(ies) of ${rows.size} row(s)" }
            val pageWord = getString(com.symmetricalpalmtree.soil.paper.R.string.backlinks_page_word)
            val documentWord = getString(com.symmetricalpalmtree.soil.paper.R.string.backlinks_document_word)
            paper.releaseRender()
            linksPanel = BacklinksPanel(
                this@CalendarActivity,
                title = getString(R.string.calendar_links_title),
                emptyText = getString(R.string.calendar_links_empty, titleOf(t)),
                rows = groups.map { BacklinksModel.Row(it, BacklinksModel.title(it.name, it.pageId, it.pageNumber, pageWord, documentWord), CalLinksModel.detail(it)) },
                onDismissed = { linksPanel = null },
                onPicked = ::followLink,
            ).also { it.show() }
        }
    }

    /** A row followed: Soil opens the page, or the document, in its app over the calendar, and
     *  Back comes back here. */
    private fun followLink(group: CalLinksModel.Group) {
        if (isFinishing || closing) return
        Slog.d(TAG) { "open a link's source: ${group.kind}" }
        val started = runCatching {
            startActivity(
                Intent(Seam.ACTION_FOLLOW).setPackage(BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_ITEM_ID, group.itemId)
                    .putExtra(Seam.EXTRA_PAGE_ID, group.pageId.takeIf { it.isNotEmpty() }),
            )
        }.isSuccess
        if (!started) Dialogs.problem(this, R.string.calendar_links_title, R.string.calendar_links_no_soil)
    }

    /**
     * Put the showing page's size and template on the paper — baked only when something the bake
     * depends on has changed ([BakeKey]). `setPageSize` and `setTemplate` are each an EPD repaint,
     * and a bake is a page-sized bitmap rasterized with forty-odd labelled cells: an undo that
     * changes neither pays for neither. The replaced bitmap is recycled — g-paper holds only the
     * one it was last given.
     */
    private fun applyTemplate(force: Boolean = false) {
        val doc = document ?: return
        val key = BakeKey(doc.target, LocalDate.now(), doc.pageWidth.toInt(), doc.pageHeight.toInt(), doc.marks)
        if (!force && key == bakeKey && baked != null) return
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
        val marks = document?.marks.orEmpty()
        return when (t.kind) {
            CalendarTarget.KIND_WEEK -> CalendarTemplate.week(weekGeometry(), t.localDate, today, density, palette, notes, marks)
            CalendarTarget.KIND_DAY -> CalendarTemplate.day(dayGeometry(), t.half, density, palette, marks[t.localDate].orEmpty())
            else -> CalendarTemplate.month(monthGeometry(), t.localDate, today, density, palette, notes, marks)
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

    // ── The clipboard ────────────────────────────────────────────────────────

    /**
     * The page sheet, on a finger long-press as the notebook's is (Greg, 2026-10-06): the whole
     * page's copy and paste — Copy page, and Paste page while the clipboard holds a page. Export…
     * joins it with phase 7. A long press asks; it never acts.
     */
    private fun showPageSheet() {
        if (!opened || closing || isFinishing || isDestroyed) return
        paper.releaseRender()
        val sheet = ActionSheetDialog(this)
            .title(getString(R.string.calendar_page_sheet_title))
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_copy, getString(R.string.calendar_copy_page)) { runPageOp { copyPage() } }
        // Absent, never disabled, while the clipboard holds no page.
        if (clipKind == ClipEnvelope.KIND_PAGE) {
            sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_clipboard, getString(R.string.calendar_paste_page)) { runPageOp { pastePage() } }
        }
        sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_export, getString(R.string.calendar_export_action)) { runPageOp { exportPage() } }
        sheet.show()
    }

    /**
     * Soil's export screen in its render-only mode, for the period showing — a Day whole, both
     * halves. The page is flushed first, so what the render service reads is what is on it; the
     * calendar stays open behind the screen (an app store is not a file to free), and Back
     * comes back to it.
     */
    private suspend fun exportPage() {
        val doc = document ?: return
        doc.flushUntilClean()
        val t = doc.target
        Slog.d(TAG) { "export: ${RenderKey.of(t)}" }
        startActivity(
            Intent(Seam.ACTION_EXPORT).setPackage(BuildConfig.SOIL_PACKAGE)
                .putExtra(Seam.EXTRA_RENDER_KIND, RenderKey.KIND)
                .putExtra(Seam.EXTRA_RENDER_KEY, RenderKey.of(t))
                .putExtra(Seam.EXTRA_RENDER_NAME, RenderKey.stemOf(t)),
        )
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
     * Copy the lasso's strokes to the clipboard as the pad does: plain ink, an objects payload on
     * the notebook slot, so a notebook or the pad pastes it with the Paste it has. The page is
     * flushed first, under the page-op lock; nothing here changes.
     */
    private fun copySelection(sel: Selection) {
        if (!opened || closing) return
        val ids = sel.strokeIds.toHashSet()
        runPageOp {
            val doc = document ?: return@runPageOp
            doc.flushUntilClean()
            val picked = doc.strokes.filter { it.id in ids }
            val now = System.currentTimeMillis()
            val envelope = InkClip.envelopeOf(picked, now)
            if (envelope == null) {
                Dialogs.problem(this, R.string.calendar_nothing_to_copy_title, R.string.calendar_nothing_to_copy_body)
                return@runPageOp
            }
            val bytes = if (InkWire.withinLimits(picked)) ClipEnvelope.encode(envelope) else null
            if (bytes == null) {
                Dialogs.problem(this, R.string.calendar_too_large_title, R.string.calendar_too_large_body)
                return@runPageOp
            }
            if (!putClip(envelope, bytes)) return@runPageOp
            Slog.d(TAG) { "copied ${envelope.rows.size} strokes to the clipboard" }
            Toast.makeText(this, R.string.calendar_copied_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copy page: the showing page — a Day, both halves, AM then PM — as a notebook page clip
     * papered with its grid. The showing page is flushed first; the other half is read as stored.
     * The grid is rendered alone (no ring, no marks): paper, not a snapshot of today.
     */
    private suspend fun copyPage() {
        val doc = document ?: return
        val t = doc.target
        val ink = doc.captureInk()
        val w = doc.pageWidth
        val h = doc.pageHeight
        val halves = if (t.kind == CalendarTarget.KIND_DAY) {
            val other = CalendarTarget.of(CalendarTarget.KIND_DAY, t.localDate, if (t.half == CalendarTarget.HALF_AM) CalendarTarget.HALF_PM else CalendarTarget.HALF_AM)
            val store = (application as CalsproutApp).calendar()
            val stored = withContext(Dispatchers.IO) { store.readPage(other) }
            listOf(t to ink, other to stored.strokes).sortedBy { it.first.half }
        } else {
            listOf(t to ink)
        }
        // The grids are page-sized bitmaps encoded to WEBP, and the envelope is every stroke
        // encoded: none of it on Main.
        val now = System.currentTimeMillis()
        val (pages, envelope, bytes) = withContext(Dispatchers.Default) {
            val pages = halves.map { (target, strokes) -> InkClip.PageInk(w, h, gridBytes(target), strokes) }
            val envelope = InkClip.pageEnvelopeOf(pages, now) { CalendarStore.newId() }
            val strokes = pages.flatMap { p -> p.strokes.map { it.second } }
            val bytes = if (envelope != null && InkWire.withinLimits(strokes)) ClipEnvelope.encode(envelope) else null
            Triple(pages, envelope, bytes)
        }
        val strokes = pages.flatMap { p -> p.strokes.map { it.second } }
        if (envelope == null || bytes == null) {
            Dialogs.problem(this, R.string.calendar_too_large_title, R.string.calendar_page_too_large_body)
            return
        }
        if (!putClip(envelope, bytes)) return
        Slog.d(TAG) { "copied ${pages.size} page(s), ${strokes.size} strokes, ${bytes.size} bytes" }
        Toast.makeText(this, R.string.calendar_copied_toast, Toast.LENGTH_SHORT).show()
    }

    /** The grid of [t] alone, at the page's size, as WEBP: the paper a pasted page gets. */
    private fun gridBytes(t: CalendarTarget): ByteArray {
        val density = resources.displayMetrics.density
        val notes = getString(R.string.calendar_notes_label)
        val bmp = when (t.kind) {
            CalendarTarget.KIND_WEEK -> CalendarTemplate.week(weekGeometry(), t.localDate, null, density, palette, notes)
            CalendarTarget.KIND_DAY -> CalendarTemplate.day(dayGeometry(), t.half, density, palette)
            else -> CalendarTemplate.month(monthGeometry(), t.localDate, null, density, palette, notes)
        }
        return try {
            BuiltInTemplates.toWebp(bmp)
        } finally {
            bmp.recycle()
        }
    }

    /** Put [envelope] on the notebook slot. False, after a dialog, when Soil would not take it. */
    private suspend fun putClip(envelope: ClipEnvelope, bytes: ByteArray): Boolean {
        val written = withContext(Dispatchers.IO) {
            runCatching {
                (application as CalsproutApp).soil.seam().putClip(InkClip.SLOT, SeamClip(envelope.kind, envelope.sourceNotebookId, envelope.copiedAt), SeamShared.write(bytes))
            }.onFailure { Log.w(TAG, "the clipboard was not written: ${it.javaClass.simpleName}") }.isSuccess
        }
        if (!written) {
            Dialogs.problem(this, R.string.calendar_copy_failed_title, R.string.calendar_copy_failed_body)
            return false
        }
        clipKind = envelope.kind
        return true
    }

    /** The clipboard's payload, or null for none or unreadable. IO. */
    private suspend fun readClip(): ClipEnvelope? = withContext(Dispatchers.IO) {
        runCatching {
            val seam = (application as CalsproutApp).soil.seam()
            ClipEnvelope.decode(seam.clip(InkClip.SLOT)?.let { SeamShared.readAndClose(it) })
        }.onFailure { Log.w(TAG, "the clipboard was not read: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * The lasso's Paste: the clipboard's ink onto the showing page under fresh ids — centred on
     * the stylus tap, or at the source coordinates from the popup's row — selected with the lasso
     * armed so the pen can drag it on at once. Anything but an objects payload — gone, or a page
     * copied since — is refused with a dialog and the mark drops.
     */
    private suspend fun pasteStrokes(tapX: Float?, tapY: Float?) {
        val env = readClip()
        if (env == null || env.kind != ClipEnvelope.KIND_OBJECTS) {
            clipKind = env?.kind
            Dialogs.problem(this, R.string.calendar_paste_failed_title, R.string.calendar_paste_failed_body)
            return
        }
        land(InkClip.strokesOf(env), tapX, tapY)
    }

    /**
     * The page sheet's Paste page: a copied page's ink onto the showing page **at its own
     * coordinates** — the calendar has no page to insert, so the page's layout is what carries
     * over (a notebook page's, a pad page's, another calendar page's; of a Day's two, the first).
     */
    private suspend fun pastePage() {
        val env = readClip()
        if (env == null || env.kind != ClipEnvelope.KIND_PAGE) {
            clipKind = env?.kind
            Dialogs.problem(this, R.string.calendar_paste_page_failed_title, R.string.calendar_paste_page_failed_body)
            return
        }
        val firstPage = env.rows.firstOrNull { it.type == "page" }?.id
        val onFirst = env.rows.filter { it.parentId == firstPage }.mapTo(HashSet()) { it.id }
        val strokes = InkClip.strokesOf(env).filter { firstPage == null || it.id in onFirst }
        land(strokes, null, null)
    }

    private suspend fun land(strokes: List<Stroke>, tapX: Float?, tapY: Float?) {
        val doc = document ?: return
        val placed = if (tapX != null && tapY != null) InkPlacement.centredOn(strokes, tapX, tapY, doc.pageWidth, doc.pageHeight) { CalendarStore.newId() }
        else InkPlacement.atSource(strokes, doc.pageWidth, doc.pageHeight) { CalendarStore.newId() }
        if (placed.isEmpty()) {
            Dialogs.problem(this, R.string.calendar_paste_failed_title, R.string.calendar_paste_empty_body)
            return
        }
        val action = doc.paste(placed) ?: return
        record(action)
        showPage(firstLoad = false)
        landSelected(placed)
        Slog.d(TAG) { "pasted ${placed.size} strokes (${if (tapX != null) "at the tap" else "at source"})" }
    }

    /** What arrived, selected with the lasso armed. */
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

    /** Throw the clipboard away, in Soil and in the mark. Never throws. */
    private fun clearClipboard() {
        if (!opened || closing) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { (application as CalsproutApp).soil.seam().clearClip(InkClip.SLOT) } }
            clipKind = null
            Toast.makeText(this@CalendarActivity, R.string.calendar_clipboard_cleared_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** The clipboard is the library's: a notebook or the pad may have copied to it while this
     *  screen was behind. A failed read leaves what was known. */
    private fun refreshClipHeader() {
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching { (application as CalsproutApp).soil.seam().clipHeader(InkClip.SLOT)?.payloadKind }
            }
            if (read.isSuccess) clipKind = read.getOrNull()
        }
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
        refreshClipHeader()
        // A date rolled over while the screen sat in the background: the ring moves with it. Only
        // when it did — a resume is otherwise not a frame. The day the showing template was baked
        // for is part of the bake key, so a changed `today` is a changed key, and a changed key
        // is a bake.
        // Another calendar screen (a `cal:` link starts one) may have written the showing page
        // while this one was behind — minted its row, or inked it: read it again, and put it on
        // the paper only when it changed.
        if (opened && !closing) runPageOp { if (document?.reload() == true) showPage(firstLoad = false) }
        val key = bakeKey ?: return
        if (opened && !closing && key.today != LocalDate.now()) applyTemplate()
    }

    override fun onPause() {
        (application as CalsproutApp).left(this)
        super.onPause()
    }

    override fun onScreenDestroyed() {
        super.onScreenDestroyed()
        linksPanel?.dismiss()
        linksPanel = null
    }

    private companion object {
        const val TAG = "CalendarActivity"
    }
}
