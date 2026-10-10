package com.symmetricalpalmtree.soil.paper.ink

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.chrome.ChromeBand
import com.symmetricalpalmtree.soil.paper.chrome.ChromeToggle
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedChrome
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedTools
import com.symmetricalpalmtree.soil.paper.chrome.EraserBar
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.paper.chrome.SharedChrome
import com.symmetricalpalmtree.soil.paper.chrome.asBar
import com.symmetricalpalmtree.soil.paper.R
import android.view.KeyEvent
import kotlinx.coroutines.launch

/**
 * The **chrome and handoff half** of a paper screen — everything a screen with a g-paper surface
 * needs that knows nothing about *what is on the paper*.
 *
 * [InkScreenActivity] is a screen whose page is **strokes in a row store**: it owns a page-op
 * lock, an ink document, an undo stack of stroke edits and a lasso selection. A **raster** screen
 * shares none of that and all of *this*: the two thin bars, the chrome toggle and its collapsed
 * corner button, the eraser sub-bar, the free band, the stylus-vs-finger dispatch, the pen-idle
 * gate, the problem dialog, and above all **the EPD handoff**, which is the part no screen may get
 * wrong twice.
 *
 * So the split is by *what the code knows*, not by subject: nothing here mentions a page, a stroke,
 * a store or a selection, and [InkScreenActivity] extends it with every one of those intact.
 *
 * **The EPD handoff order is g-paper's law.** A caller that owns a g-paper surface releases
 * (`releaseForHandoff()`) immediately before launching us, we reclaim in [onResume]
 * (`resumeDrawing`), and **every** exit goes through [finishWithHandoff] — `releaseForHandoff()`
 * and then `finish()`. A failure there is fixed in g-paper, never worked around here.
 *
 * The subclass assigns [paper], [chrome], [gestures] and [eraserBar] in its `onCreate`.
 *
 * **Frame silence:** no app frame while `paper.isPenActive`. Chrome text goes through
 * [PenIdle.whenIdle] ([whenPenIdle]); the frames that do not are recorded exceptions.
 */
abstract class PaperScreenActivity : AppCompatActivity() {

    /** The surface. The subclass creates it in `onCreate` and assigns it here. */
    protected lateinit var paper: PaperView
    protected lateinit var chrome: PaperChrome
    protected lateinit var gestures: PageGestures

    /** The eraser button's sub-bar (arc 29 / LE3) — Point · Lasso. Assigned in `onCreate` after
     *  the toolbar, because a pick lands on the toolbar's `arm`. */
    protected lateinit var eraserBar: EraserBar

    /** The collapsed chrome (arc 36 / C2) — the corner tool button and its two rows, what this
     *  screen shows while the bars are hidden. Built by [initChrome], before the toggle that flips
     *  the button with them; never initialised on a screen whose layout carries no corner button. */
    protected lateinit var collapsed: CollapsedChrome

    /** Both bars' hide / show (arc 33 / F3). Built by [initChrome], which the subclass calls in
     *  `onCreate` once [paper], [eraserBar] and the bar views exist and right after its root
     *  layout-change listener — the notebook's place for it. */
    protected lateinit var chromeToggle: ChromeToggle

    protected var opened = false
    protected var closing = false

    /**
     * The Supernote's side bars are keys, and a paper screen in front receives them in its own
     * window like any other key. They are handed to [onBarKey] and left unconsumed, so nothing
     * else that listens for them is blinded. The subclass decides where they go: Soil's own paper
     * hands them to the shell in its process, a Sprout app's paper sends them over the seam.
     * (The shell's own system-wide key filter is off while a paper screen is in front, because
     * that filter is what let a resting palm cut the pen's stream, 2026-10-03.)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in BAR_KEY_FIRST..BAR_KEY_LAST) onBarKey(event)
        return super.dispatchKeyEvent(event)
    }

    /** A side-bar key, as the window received it. Observed only; nothing is consumed. */
    protected open fun onBarKey(event: KeyEvent) {}

    private var problemShowing = false

    // ── What the consumer supplies ───────────────────────────────────────────

    protected abstract val logTag: String

    /** The inflated root, or **null** before it exists. */
    protected abstract val screenRoot: View?
    protected abstract val topBarView: View?
    protected abstract val bottomBarView: View?
    protected abstract val openingOverlay: View?

    /** The top bar's eraser button — the one contact that never dismisses the eraser sub-bar
     *  (its own re-tap toggles it; a dismissal here would make the toggle reopen what it closed). */
    protected abstract val eraserButtonView: View?

    /** The top bar's Back button — the first entry of the collapsed chrome's overflow row (arc 36 /
     *  C2), mirrored: the row's button performs this one's own click, never a copy of its handler. */
    protected abstract val backButtonView: View?

    /** The collapsed chrome's three views (arc 36 / C2) — the corner tool button, the mini toolbar
     *  and the overflow row, declared in the screen's own layout after every bar they may overlap.
     *  All three null (a screen that has not grown them) = no collapsed chrome at all. */
    protected abstract val collapsedKnobView: ImageButton?
    protected abstract val collapsedBarView: LinearLayout?
    protected abstract val collapsedOverflowView: LinearLayout?

    /**
     * Arm [tool] from the host side on the screen's own toolbar (`toolbar.arm`) — what a pick from
     * the mini toolbar lands on. It exists for `arm`'s own reason: a host-set tool is never echoed
     * back as `onToolChanged`, so the bar has to be told by hand.
     */
    protected abstract fun armTool(tool: Tool)

    /**
     * The overflow row's entries, Back first (decision 5). The default is Back alone — every screen
     * has one and nothing else is universal; the pad adds Send, the calendar its doors. Read once,
     * at [initChrome]: what a *button* shows is mirrored at every open, but which buttons exist is
     * the screen's shape and does not change.
     */
    protected open fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(backEntry())

    /**
     * Which tools the mini toolbar carries (arc 43 / K2). The four every paper screen has had; a
     * screen whose surface answers fewer — the sketch's pencil and rubbing eraser — says so here
     * rather than showing buttons that arm a tool its surface does not have.
     */
    protected open fun collapsedTools(): List<Tool> = CollapsedTools.ORDER

    /**
     * The PEN slot's **two kinds** on the mini toolbar (arc 44 / T3), or null — every screen but
     * the sketch face, whose pencil and gel pen are both `Tool.PEN` and so cannot be two entries of
     * [collapsedTools]. Read once, at [initChrome], like [collapsedOverflow]: which buttons exist
     * is the screen's shape, and only what they *show* is read at every open.
     */
    protected open fun collapsedPenKinds(): CollapsedChrome.PenKinds? = null

    /**
     * The one pen's painted glyph on a screen with **one** pen (arc 49 / P4 — the writing faces'
     * ballpen filled with its shade), or null to wear the plain resource one. Read once, at
     * [initChrome], like [collapsedPenKinds]; ignored on a screen that offers kinds.
     */
    protected open fun collapsedPenIcon(): (() -> CollapsedChrome.PenIcon)? = null

    /**
     * What a pick of the **already-armed** one pen on the mini toolbar does (arc 49 / P4), handed
     * the row's own button as an anchor — the writing faces hang their shade panel under it and the
     * rows stay up beneath. Null = a re-pick arms again and closes the rows, as before P4.
     */
    protected open fun collapsedPenReTap(): ((anchor: View) -> Unit)? = null

    /**
     * The rows are about to come down, by any path (arc 44 / T3) — the screen takes down the
     * sub-bars it hung off them, **without** pushing exclusions: [CollapsedChrome]'s own
     * `onChanged` follows and a close stays one binder call. The eraser sub-bar is not one of
     * these: it belongs to the top bar's eraser button and goes down when the rows come *up*.
     */
    protected open fun onCollapsedClosing() {}

    /**
     * Whether a contact at this point (root view-local) lands inside a sub-bar this screen has hung
     * off the rows, and so must **not** take them down (arc 44 / T3) — [CollapsedChrome]'s `keep`
     * predicate, the notebook's rule for its Insert bar. Nothing by default: a screen with no
     * sub-bar of its own has nothing to keep alive.
     */
    protected open fun keepCollapsedUnder(x: Int, y: Int): Boolean = false

    /**
     * Back as a mirrored entry, or null before the bar exists. The hint is the button's own content
     * description — the screens name Back differently ("Back to the notebook", "Back to the
     * calendar") and the row should say what the bar's long press says.
     */
    protected fun backEntry(): CollapsedChrome.Entry? =
        backButtonView?.let { CollapsedChrome.Entry.mirroring(R.drawable.ic_arrow_left, it) }

    // ── Chrome ───────────────────────────────────────────────────────────────

    protected fun pushExclusions() {
        if (::chrome.isInitialized) chrome.pushExclusions()
    }

    /**
     * Floating bars this screen owns beyond the two every paper screen has — [InkScreenActivity]'s
     * lasso selection bar, and whatever a future one hangs over its paper. Window coordinates, read
     * fresh; empty by default.
     */
    protected open fun extraFloatingRects(): List<Rect> = emptyList()

    /** [extraFloatingRects]' hit test, in root view-local coordinates. */
    protected open fun extraFloatingContains(x: Int, y: Int): Boolean = false

    /** Every floating bar's rect, in window coordinates — the [PaperChrome] `extraRects` supplier.
     *  The corner button and its rows are chrome like any other (arc 36 / C2): the pen refuses
     *  under them. */
    protected fun floatingRects(): List<Rect> =
        extraFloatingRects() +
            (if (::eraserBar.isInitialized) eraserBar.rects() else emptyList()) +
            (if (::collapsed.isInitialized) collapsed.rects() else emptyList())

    /** The matching hit test in root view-local coordinates — the `extraContains` supplier. */
    protected fun floatingContains(x: Int, y: Int): Boolean =
        extraFloatingContains(x, y) ||
            (::eraserBar.isInitialized && eraserBar.contains(x, y)) ||
            (::collapsed.isInitialized && collapsed.contains(x, y))

    // ── The collapsed chrome (arc 36 / C2) ───────────────────────────────────

    /** The collapsed chrome's repaint (arc 36) — what the subclass wires into its toolbar's
     *  `onSynced`, the one funnel every tool change passes through (a bar tap, `arm`, every by-hand
     *  sync, `onToolChanged`), so the corner button can never be left out of one of them. */
    protected fun syncCollapsed() {
        if (::collapsed.isInitialized) collapsed.sync()
    }

    /** The collapsed chrome's lasso wears the clipboard mark while objects are on the clipboard. */
    protected fun collapsedClipboardLoaded(loaded: Boolean) {
        if (::collapsed.isInitialized) collapsed.showClipboardLoaded(loaded)
    }

    /** Both of the corner button's rows down. Idempotent, and safe before the chrome is built —
     *  every page swap and every exit calls it beside [hideEraserBar], for the same reason. */
    /** Whether ([x], [y]) lands on the corner button or its rows. */
    protected fun collapsedContains(x: Int, y: Int): Boolean =
        ::collapsed.isInitialized && collapsed.contains(x, y)

    protected fun dismissCollapsed() {
        if (::collapsed.isInitialized) collapsed.dismiss()
    }

    /** The outside-contact dismissal — the rule lives in [CollapsedChrome]; a screen that hangs a
     *  sub-bar off the rows (arc 44 / T3, the sketch face's `PencilBar`) keeps it alive under a
     *  contact of its own through [keepCollapsedUnder], and every other screen answers false. */
    private fun dismissCollapsedOnContact(ev: MotionEvent, index: Int) {
        if (!::collapsed.isInitialized) return
        collapsed.dismissOnContact(ev.getX(index).toInt(), ev.getY(index).toInt()) { x, y ->
            keepCollapsedUnder(x, y)
        }
    }

    // ── The eraser sub-bar (arc 29 / LE3) ────────────────────────────────────

    /** A second tap on the armed eraser opens the sub-bar; a third closes it — the notebook's toggle. */
    /**
     * Every floating bar down: the eraser's sub-bar, and whatever a screen hangs of its own (the
     * shade panel). Called before the chrome flips and as the collapsed rows open.
     */
    protected open fun hideFloatingBars() {
        hideEraserBar()
    }

    /** A contact landed: a screen closes any floating bar of its own that it is outside of. */
    protected open fun dismissFloatingOnContact(ev: MotionEvent, index: Int) {}

    protected fun toggleEraserBar() {
        if (::eraserBar.isInitialized && eraserBar.isShowing) hideEraserBar() else showEraserBar()
    }

    /**
     * Open the eraser's sub-bar — Point · Lasso. Gated on the page actually being on the paper (the
     * block-all rect's reason), and **not** pen-idle gated: one chrome frame at a deliberate tap,
     * with the pen that tapped it still hovering (the notebook's floating-bar rule, ledgered there).
     */
    protected fun showEraserBar() {
        if (!opened || closing || !::eraserBar.isInitialized) return
        if (eraserBar.show()) pushExclusions()
    }

    /** Idempotent — every dismiss path calls it without checking. */
    protected fun hideEraserBar() {
        if (!::eraserBar.isInitialized || !eraserBar.isShowing) return
        eraserBar.hide()
        pushExclusions()
    }

    /**
     * The outside-contact dismissal — the sticky editor's rule: any pointer landing anywhere but
     * the bar itself or the eraser button takes the bar down. That covers a bare pen tap, a stroke,
     * a finger gesture and **every other button on either bar** (Today, Events, the pager, Send…)
     * without each of them having to know the sub-bar exists.
     */
    private fun dismissEraserBarOnContact(ev: MotionEvent, index: Int) {
        if (!::eraserBar.isInitialized || !eraserBar.isShowing) return
        val x = ev.getX(index).toInt(); val y = ev.getY(index).toInt()
        if (eraserButtonView?.let { PaperToolbar.rectOf(it) }?.contains(x, y) == true) return
        if (eraserBar.contains(x, y)) return
        hideEraserBar()
    }

    /**
     * The free band between the two bars, in root coordinates — [ChromeBand]'s rule (arc 33): a
     * hidden bar contributes the root's edge, so a floating bar can still be placed while the
     * chrome is hidden; a shown bar not yet laid out withholds the band. Null before the root has
     * a height.
     */
    protected fun chromeBand(): IntRange? {
        val root = screenRoot ?: return null
        // The edge facing the paper, read here: a `GONE` bar still reports its last laid-out edges.
        return ChromeBand.of(
            rootHeight = root.height,
            top = topBarView?.let { it.asBar(edge = it.bottom) },
            bottom = bottomBarView?.let { it.asBar(edge = it.top) },
        )
    }

    // ── The chrome toggle (arc 33 / F3) ──────────────────────────────────────

    /**
     * The app's **local fallback** for the chrome's state — what the subclass remembered from
     * last time, used only while Soil's shared flag ([readSharedChromeHidden]) has not answered.
     * A rebuilt Activity's own saved state wins over it.
     */
    protected open val initialChromeHidden: Boolean get() = false

    /** The chrome was flipped to [hidden] — by the person, or to follow Soil's shared flag — the
     *  subclass's moment to remember it locally, as the fallback. */
    protected open fun onChromeChanged(hidden: Boolean) {}

    /**
     * **Soil's shared flag** ([SharedChrome]): one hidden state for every paper screen of every
     * app. Read as the screen comes to the front; null when Soil cannot say, and the local state
     * stands. The subclass does the crossing **off the main thread** and never throws.
     */
    protected open suspend fun readSharedChromeHidden(): Boolean? = null

    /** The person flipped the chrome: hand it to Soil, off the main thread, fire and forget. Not
     *  called for a flip that only follows Soil's flag, nor for the first build. */
    protected open fun writeSharedChromeHidden(hidden: Boolean) {}

    /** Soil's answer when it arrived before [chromeToggle] was built: [initChrome] opens in it. */
    private var sharedChromeEarly: Boolean? = null

    /** True while a flip is not the person's (the first build, following Soil): nothing to tell Soil. */
    private var chromeQuiet = false

    /** Counts the person's flips: an answer from Soil read before one is stale, and is dropped. */
    private var chromeFlips = 0

    /**
     * Build [chromeToggle] over both bars and put the chrome into the state this screen should
     * open in, before the first layout, so a screen opened hidden never shows its bars. The eraser
     * sub-bar goes down at a hide (its button is about to go); the selection bar stays, a lasso
     * being a deliberate act. After the flip's relayout the exclusions are re-pushed once — the
     * band and every rect are read fresh.
     *
     * **[savedInstanceState] wins over [initialChromeHidden]**: a rebuilt Activity has a flip of
     * the person's own since it was launched. Absent on both sides = shown.
     */
    protected fun initChrome(savedInstanceState: Bundle? = null) {
        val root = screenRoot ?: return
        initCollapsed(root)
        chromeToggle = ChromeToggle(
            paper = paper,
            root = root,
            bars = listOfNotNull(topBarView, bottomBarView),
            beforeHide = { hideFloatingBars() },
            afterLayout = { pushExclusions() },
            onChanged = {
                onChromeChanged(it)
                if (!chromeQuiet) { chromeFlips++; writeSharedChromeHidden(it) }
            },
            // Arc 36 / C2: the corner button lives exactly as long as the bars do not, and the
            // rows hung under it go down before they come back.
            whileHidden = listOfNotNull(collapsedKnobView),
            beforeShow = { dismissCollapsed() },
        )
        val saved = savedInstanceState?.takeIf { it.containsKey(KEY_CHROME_HIDDEN) }
            ?.getBoolean(KEY_CHROME_HIDDEN)
        val hidden = SharedChrome.opening(shared = sharedChromeEarly, saved = saved, local = initialChromeHidden)
        // The first build is not a flip of the person's: Soil is not told it (a local fallback
        // written over the shared flag would undo another screen's flip).
        chromeQuiet = true
        try { chromeToggle.apply(hidden, releaseRender = false) } finally { chromeQuiet = false }
    }

    /**
     * Ask Soil for the shared flag, and follow it when it differs: another paper screen, in this
     * app or another, may have flipped it while this one was away. Off the main thread; an answer
     * that arrives after the person flipped this screen is stale and dropped. Pen-idle-gated,
     * since it is not a deliberate act on this screen.
     */
    private fun followSharedChrome() {
        val flipsAtAsk = chromeFlips
        lifecycleScope.launch {
            val shared = readSharedChromeHidden() ?: return@launch
            if (chromeFlips != flipsAtAsk || closing) return@launch
            if (!::chromeToggle.isInitialized) { sharedChromeEarly = shared; return@launch }
            whenPenIdle {
                if (chromeFlips != flipsAtAsk || closing || isFinishing) return@whenPenIdle
                val target = SharedChrome.adopt(shared, chromeToggle.hidden) ?: return@whenPenIdle
                chromeQuiet = true
                try { chromeToggle.apply(target, releaseRender = opened) } finally { chromeQuiet = false }
            }
        }
    }

    /**
     * Build the collapsed chrome (arc 36 / C2) — **before** the toggle, which flips the corner
     * button with the bars and would otherwise have nothing to flip. A screen whose layout carries
     * no corner button (or whose root is not a [ViewGroup] to hang the rows in) simply has none:
     * every call site is `isInitialized`-guarded, as the eraser sub-bar's are.
     *
     * What the screen says is only what differs — its tools, its overflow entries and how it arms
     * a tool. Everything else is [CollapsedChrome]'s; the corner button repaints through the
     * toolbar's `onSynced` ([syncCollapsed]).
     */
    private fun initCollapsed(root: View) {
        val knob = collapsedKnobView ?: return
        val miniBar = collapsedBarView ?: return
        val overflowBar = collapsedOverflowView ?: return
        val group = root as? ViewGroup ?: return
        collapsed = CollapsedChrome(
            root = group,
            knob = knob,
            miniBar = miniBar,
            overflowBar = overflowBar,
            paper = paper,
            bandBottom = { chromeBand()?.last },
            canOpen = { opened && !closing },
            // Back at the top of the column (Greg, 2026-10-10), wherever a screen listed it.
            leading = listOfNotNull(backEntry()),
            overflow = collapsedOverflow().filter { it.mirrors !== backButtonView },
            // The eraser's own sub-bar is the one other thing that could be up: it belongs to the
            // bar's eraser button, which is not on the glass while the rows are.
            onOpen = { hideFloatingBars() },
            // Arc 44 / T3: a sub-bar the screen hung off the rows goes down with them, before the
            // one exclusion push — a close stays one binder call.
            onClose = { onCollapsedClosing() },
            onArmed = { armTool(it) },
            onChanged = { pushExclusions() },
            tools = collapsedTools(),
            penKinds = collapsedPenKinds(),
            // Arc 49 / P4: a screen with one pen may still paint it and answer its re-tap.
            penIcon = collapsedPenIcon(),
            onPenReTap = collapsedPenReTap(),
        )
    }

    /** The chrome state survives a rebuild — it is the person's way of working. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::chromeToggle.isInitialized) outState.putBoolean(KEY_CHROME_HIDDEN, chromeToggle.hidden)
    }

    /**
     * Hide or show every bar — the finger double-tap's answer on a screen that is open and not
     * closing (the gesture cannot arm before the page lands, but the escrow can deliver a pair
     * across a close).
     */
    protected fun toggleChrome() {
        if (!opened || closing || !::chromeToggle.isInitialized) return
        chromeToggle.toggle()
    }

    /** The paper surface in px — a page stored with no size of its own takes it. Before the first
     *  layout the screen itself is the honest answer: these screens are full-bleed and portrait-locked. */
    protected fun surfaceSize(): Pair<Float, Float> {
        val v = paper.asView()
        if (v.width > 0 && v.height > 0) return v.width.toFloat() to v.height.toFloat()
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    // ── Pen gating and dialogs ───────────────────────────────────────────────

    protected fun whenPenIdle(action: () -> Unit) {
        val root = screenRoot ?: return
        PenIdle.whenIdle(paper, root, action)
    }

    /** **One problem dialog at a time** — a hand that keeps writing would otherwise stack one per
     *  stroke, and a wall of identical alerts says less than the first one did. */
    protected fun showProblem(titleRes: Int, bodyRes: Int) {
        if (isFinishing || isDestroyed || problemShowing) return
        problemShowing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener { problemShowing = false }
                .create()
        ).show()
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observer only — consumes nothing.
        if (opened && ::gestures.isInitialized) gestures.onTouchEvent(ev)
        val action = ev.actionMasked
        // Every pointer going down, not just the first: with a hand resting on the glass the pen
        // arrives as ACTION_POINTER_DOWN (the notebook's O2 finding).
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            dismissEraserBarOnContact(ev, ev.actionIndex)
            dismissFloatingOnContact(ev, ev.actionIndex)
            dismissCollapsedOnContact(ev, ev.actionIndex)
        }
        if (::chrome.isInitialized && action == MotionEvent.ACTION_DOWN) {
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            if (!stylus && !paper.isPenActive && chrome.overChrome(ev)) paper.releaseRender()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::paper.isInitialized) paper.resumeDrawing()
        followSharedChrome()
    }

    /** The durability point while backgrounded — what the subclass writes before it may be killed.
     *  Runs after `super.onPause()`, on a scope of the subclass's own choosing (ours is dead at
     *  ON_DESTROY, and a half-written page is the one thing worth surviving that). */
    protected open fun onScreenPaused() {}

    override fun onPause() {
        super.onPause()
        onScreenPaused()
    }

    /** What the subclass drops before the surface is released — pending callbacks, a session. Runs
     *  **before** `paper.release()` and before `super.onDestroy()`. */
    protected open fun onScreenDestroyed() {}

    override fun onDestroy() {
        onScreenDestroyed()
        if (::paper.isInitialized) paper.release()
        super.onDestroy()
    }

    /**
     * `releaseForHandoff()` and then `finish()` — the whole of this screen's half of the EPD
     * handoff, and the reason no exit calls `finish()` on its own. See the class note.
     */
    protected fun finishWithHandoff() {
        if (::paper.isInitialized) paper.releaseForHandoff()
        Slog.d(logTag) { "finishing (handoff released)" }
        finish()
    }

    companion object {

        /** Where [onSaveInstanceState] parks the chrome state (arc 34 / L19). */
        private const val KEY_CHROME_HIDDEN = "chromeHidden"

        /** The Supernote's bar keys: Ratta's `KEYCODE_F13..F30` (293..310); 300/301 and 309/310 are the bars. */
        const val BAR_KEY_FIRST = 293
        const val BAR_KEY_LAST = 310
    }
}
