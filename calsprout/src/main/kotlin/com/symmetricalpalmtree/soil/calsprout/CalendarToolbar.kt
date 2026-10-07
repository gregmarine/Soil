package com.symmetricalpalmtree.soil.calsprout

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec

/**
 * The calendar's chrome (Greg, 2026-10-05): Back and the three tools on the top bar, then the
 * three view latches, Today, Events and Links at its far end; the pager — prev, the
 * period's title, next — alone on the bottom bar. The tool half is `:paper`'s [PaperToolbar];
 * this adds what is the calendar's own: the fixed tool values, the navigation controls, the doors
 * and the title behind the frame-silence gate.
 *
 * The three view latches are Tabler icons; the armed one has `isSelected` set, which reads as the
 * ToolbarButton's border. It is set from [setView] on every page shown, **never from the tap that
 * asked for it**: what is latched is what is on the paper, so a navigation that failed cannot
 * leave a lie in the bar.
 *
 * **The pager's title is itself a tap target** — it opens the day picker.
 *
 * **The tools are fixed, and they are the Scratch Pad's** (Greg, 2026-10-05): one black pen at
 * [PEN_WIDTH_PX], eraser [ERASER_RADIUS_PX], no shade panel. The eraser has two kinds: a second
 * tap on the armed eraser opens the shared `EraserBar` (Point · Lasso) — the screen owns the bar,
 * this just forwards the re-tap. Smart lasso and scribble erase are armed by the screen before
 * the listener attaches.
 *
 * **Events and Links are GONE until their phases land** — GONE, never disabled: a greyed
 * control is invisible on e-ink.
 *
 * **The title waits for the pen.** Never present an app frame while [PaperView.isPenActive].
 */
class CalendarToolbar(
    private val paper: PaperView,
    topBar: View,
    btnBack: ImageButton,
    btnPen: ImageButton,
    btnEraser: ImageButton,
    private val btnLasso: ImageButton,
    private val btnMonth: View,
    private val btnWeek: View,
    private val btnDay: View,
    btnToday: View,
    btnEvents: ImageButton,
    btnLinks: ImageButton,
    btnPrev: ImageButton,
    btnNext: ImageButton,
    private val title: TextView,
    onBack: () -> Unit,
    /** Show a [CalendarTarget.KIND_MONTH] / `_WEEK` / `_DAY` page. Called for the showing view too —
     *  the screen decides that a toggle to where we already are does nothing. */
    onView: (kind: Int) -> Unit,
    /** Today, in whatever view is showing. */
    onToday: () -> Unit,
    /** The Events door. Null until the phase that builds it: the button is GONE. */
    onEvents: (() -> Unit)?,
    /** The Links door. Null until the phase that builds it: the button is GONE. */
    onLinks: (() -> Unit)?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    /** The pager's title was tapped: open the day picker. */
    onTitle: () -> Unit,
    /** A tap on the already-armed eraser: the screen toggles the eraser sub-bar. */
    onEraserReTap: () -> Unit,
    /** A tap on the already-armed lasso: the screen toggles the lasso's clipboard popup. */
    onLassoReTap: () -> Unit,
    /** Any actual tool change — the screen closes the sub-bar that belonged to the old tool. */
    onToolTapped: () -> Unit,
    /** After every sync — the collapsed chrome's corner button repaints from here. */
    onSynced: () -> Unit = {},
) {

    private val tools: PaperToolbar

    init {
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX

        tools = PaperToolbar(
            bar = topBar,
            btnBack = btnBack,
            btnPen = btnPen,
            btnEraser = btnEraser,
            btnLasso = btnLasso,
            paper = paper,
            onBack = onBack,
            onEraserReTap = onEraserReTap,
            onLassoReTap = onLassoReTap,
            onToolTapped = onToolTapped,
            onSynced = onSynced,
        )

        // Every button carries a hint naming it.
        listOf(btnPrev, btnNext, btnMonth, btnWeek, btnDay, btnToday, btnEvents, btnLinks, title).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnPrev.setOnClickListener { releaseRenderIfIdle(); onPrev() }
        btnNext.setOnClickListener { releaseRenderIfIdle(); onNext() }
        btnMonth.setOnClickListener { releaseRenderIfIdle(); onView(CalendarTarget.KIND_MONTH) }
        btnWeek.setOnClickListener { releaseRenderIfIdle(); onView(CalendarTarget.KIND_WEEK) }
        btnDay.setOnClickListener { releaseRenderIfIdle(); onView(CalendarTarget.KIND_DAY) }
        btnToday.setOnClickListener { releaseRenderIfIdle(); onToday() }
        if (onEvents != null) {
            btnEvents.visibility = View.VISIBLE
            btnEvents.setOnClickListener { releaseRenderIfIdle(); onEvents() }
        } else {
            btnEvents.visibility = View.GONE
        }
        if (onLinks != null) {
            btnLinks.visibility = View.VISIBLE
            btnLinks.setOnClickListener { releaseRenderIfIdle(); onLinks() }
        } else {
            btnLinks.visibility = View.GONE
        }
        title.setOnClickListener { releaseRenderIfIdle(); onTitle() }
        title.text = ""
    }

    /** Latch the toggle for the page that is **showing** — driven from the screen's `showPage`, the
     *  way [sync] is driven from `onToolChanged` rather than from a tap. The change rides the
     *  navigation's own frame; it is never a frame of its own. */
    fun setView(kind: Int) {
        btnMonth.isSelected = kind == CalendarTarget.KIND_MONTH
        btnWeek.isSelected = kind == CalendarTarget.KIND_WEEK
        btnDay.isSelected = kind == CalendarTarget.KIND_DAY
    }

    /** Make the tool buttons honest — driven from `PaperListener.onToolChanged`, never from a tap:
     *  smart lasso arms LASSO and restores PEN on its own. */
    fun sync(tool: Tool) = tools.sync(tool)

    /** Arm [tool] from our side and sync the buttons — what the eraser sub-bar's pick lands on. */
    fun arm(tool: Tool) = tools.arm(tool)

    /** The lasso wears the clipboard mark while ink is on the clipboard — the notebook's hint
     *  that a re-tap on it will offer Paste. Idempotent. */
    fun showClipboardLoaded(loaded: Boolean) {
        if (clipboardLoaded == loaded) return
        clipboardLoaded = loaded
        btnLasso.setImageResource(
            if (loaded) com.symmetricalpalmtree.soil.paper.R.drawable.ic_lasso_clipboard else com.symmetricalpalmtree.soil.paper.R.drawable.ic_lasso,
        )
    }

    private var clipboardLoaded = false

    /** The period's title, presented only once the pen is idle (the frame-silence rule). */
    fun setTitle(text: String) {
        whenPenIdle { title.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) = PenIdle.whenIdle(paper, title, action)

    /** The [PaperView.releaseRender] contract: pen-gated, or a tap inside the pen-up tail can cost
     *  a live stroke. While the pen is active nobody is looking at a pressed state anyway. */
    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        /** The one pen width, in px — the pad's and the notebook's, so every surface writes alike. */
        const val PEN_WIDTH_PX = 3f

        /** The one eraser hit radius, in px — g-paper's default. */
        const val ERASER_RADIUS_PX = 15f
    }
}
