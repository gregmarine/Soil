package com.symmetricalpalmtree.soil.sketchsprout.sketch

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.paper.chrome.ShadeIcon
import com.symmetricalpalmtree.soil.sketchsprout.R

/**
 * The sketch screen's chrome: Back, the pencil, the gel pen, the rubber and the smudge on the top
 * bar (Guides joins in phase 5), the sketchbook's name between the groups; the pager on the
 * bottom bar. **The arrows no-op at a bound, never disable**: a greyed control is invisible on
 * e-ink, so a turn at either edge simply stays put. The tool half is `:paper`'s [PaperToolbar] — with **no lasso button**, which is
 * what `btnLasso`'s nullability is for: a raster page has no objects to select.
 *
 * - **A graphite pencil of one width and sixteen shades**, and **one gel pen** of one width and
 *   the same sixteen shades ([SketchPalette]). Both are [Tool.PEN] to the engine; what differs is
 *   only what the engine is armed with, which is what [SketchToolState] holds and this class
 *   assigns. **Each pen button wears its own shade** as a fill inside a glyph whose outline stays
 *   solid black ([reportShades]) — the colour rule's one standing opening: greys are ink, and a
 *   control may carry the armed ink only where the ink itself is chosen or reported.
 * - **A re-tap on the armed pen button of either kind** opens the shade panel under that button,
 *   for that kind; a second re-tap closes it ([onPenReTap]).
 * - **The rubbing eraser** at [ERASER_RADIUS_PX] on g-paper's [RasterRubbing] defaults. No
 *   sub-bar, never remembered.
 * - **The stylus smudge** ([Tool.SMUDGE]) at [SMUDGE_TOOL_RADIUS_PX] — the finger rub done with
 *   the nib, a stump. No options, not remembered — the rubber's rule.
 * - **No pen gestures.** `smartLassoEnabled` and `scribbleEraseEnabled` are both off.
 *
 * **The indicator and the title wait for the pen.** Never present an app frame while
 * [PaperView.isPenActive].
 */
class SketchToolbar(
    private val paper: PaperView,
    topBar: View,
    btnBack: ImageButton,
    btnPencil: ImageButton,
    /** The gel pen — [Tool.PEN]'s second **kind**, beside the pencil's. */
    btnPen: ImageButton,
    btnEraser: ImageButton,
    /** The stylus smudge — [Tool.SMUDGE]. */
    btnSmudge: ImageButton,
    private val title: TextView,
    private val pageIndicator: TextView,
    btnPrevPage: ImageButton,
    btnNextPage: ImageButton,
    onBack: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    /** Any actual tool change — the screen takes down anything that belonged to the old tool. A
     *  pencil↔gel-pen switch is one of these, even though `paper.tool` never moves for it. */
    onToolTapped: () -> Unit,
    /** A tap on the **already-armed** pen of either kind — the screen toggles its shade panel
     *  under that button, for that kind. */
    onPenReTap: (alt: Boolean) -> Unit = {},
    /** A pen **kind** was tapped: the screen applies the new state ([apply]) and remembers it. It
     *  fires before the tool is armed — the firmware pen is re-armed from the colour and width, so
     *  the kind has to be in place first. */
    onPenKindPicked: (alt: Boolean) -> Unit = {},
    /** After every sync — the collapsed chrome's corner button repaints from here. */
    onSynced: () -> Unit = {},
) {

    /** What the tools are set to. It lives here because the *engine* cannot hold it: both kinds
     *  are [Tool.PEN], so "which pen is armed" has nowhere else to be. */
    private var toolState: SketchToolState = SketchToolState.DEFAULT

    /** The armed tools, for the screen's own prefs and for the bars that paint from them. */
    val state: SketchToolState get() = toolState

    private val tools: PaperToolbar

    /** The two pen buttons' glyphs: each an outline over a body that carries that kind's shade.
     *  Built here because they are two-layer drawables whose fill this class re-inks. */
    private val pencilIcon = ShadeIcon.pencil(btnPencil.context, SketchToolState.DEFAULT.pencilReport)
        .also { btnPencil.setImageDrawable(it) }
    private val penIcon = ShadeIcon.pen(btnPen.context, SketchToolState.DEFAULT.penReport)
        .also { btnPen.setImageDrawable(it) }

    /** The shades the two buttons are actually wearing — ARGBs, so two levels that render the
     *  same could never both repaint. Null until the first report. */
    private var reportedPencil: Int? = null
    private var reportedPen: Int? = null

    init {
        paper.tool = Tool.PEN
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.rasterRubbing = RasterRubbing()
        paper.smudgeToolRadius = SMUDGE_TOOL_RADIUS_PX
        // The defaults, until the screen restores what this device remembers (before the first
        // mark is possible). Assigned directly rather than through [apply], which syncs a toolbar
        // that does not exist yet.
        assign(toolState)

        tools = PaperToolbar(
            bar = topBar,
            btnBack = btnBack,
            btnPen = btnPencil,
            btnEraser = btnEraser,
            // No lasso on a raster page — the whole reason `PaperToolbar` grew a nullable one.
            btnLasso = null,
            paper = paper,
            onBack = onBack,
            // A re-tap on the armed eraser opens the Point · Lasso sub-bar on every other paper
            // screen. Here there is no second eraser to reach, so it is honestly nothing.
            onEraserReTap = {},
            onToolTapped = onToolTapped,
            onSynced = onSynced,
            // The pen's two kinds. `altPenArmed` is read at every sync, never cached there — this
            // class's field is the one copy of that answer.
            btnAltPen = btnPen,
            altPenArmed = { toolState.isPen },
            onPenKindPicked = onPenKindPicked,
            onPenReTap = onPenReTap,
            btnSmudge = btnSmudge,
        )
        listOf(btnPrevPage, btnNextPage).forEach { androidx.appcompat.widget.TooltipCompat.setTooltipText(it, it.contentDescription) }
        btnPrevPage.setOnClickListener { PenIdle.releaseRenderIfIdle(paper); onPrevPage() }
        btnNextPage.setOnClickListener { PenIdle.releaseRenderIfIdle(paper); onNextPage() }
        pageIndicator.text = ""
    }

    /** Arm [state] — the one door every tool choice comes through: the restore at open, a kind
     *  tap, a shade from the panel. It **syncs the bar** as well as the engine, because which pen
     *  button reads as armed follows the *kind* and the tool may not have moved at all. It does
     *  not remember anything on the device: the screen owns that. */
    fun apply(state: SketchToolState) {
        assign(state)
        tools.sync(paper.tool)
    }

    /** Put the armed pen back on the engine after something else has been drawn with it — the
     *  debug fill door. A raster bake takes each stroke's own colour, width and style, never the
     *  armed pen's, so nothing is disturbed today; this is the one line that keeps that true. */
    fun restorePen() = assign(toolState)

    private fun assign(state: SketchToolState) {
        toolState = state
        paper.penStyle = state.penStyle
        paper.penWidth = state.penWidth
        paper.penColor = state.penColor
        reportShades(state)
    }

    /** Each glyph's body filled with that kind's shade, the outline solid black. Each shade is
     *  its kind's, not the armed kind's, so while the other kind or the rubber is armed a button
     *  still shows what a tap on it will bring back. Unchanged is silent, per button. */
    private fun reportShades(state: SketchToolState) {
        val pencil = state.pencilReport
        if (pencil != reportedPencil) {
            reportedPencil = pencil
            ShadeIcon.tint(pencilIcon, pencil)
        }
        val pen = state.penReport
        if (pen != reportedPen) {
            reportedPen = pen
            ShadeIcon.tint(penIcon, pen)
        }
    }

    /** Make the tool buttons honest — driven from `PaperListener.onToolChanged`, never from a tap. */
    fun sync(tool: Tool) = tools.sync(tool)

    /** Arm [tool] from the host side and sync the buttons — what the mini toolbar's pick lands on. */
    fun arm(tool: Tool) = tools.arm(tool)

    /** The sketchbook's name, presented only once the pen is idle. */
    fun setTitle(name: String) {
        PenIdle.whenIdle(paper, title) { if (title.text != name) title.text = name }
    }

    /** `n / m`, presented only once the pen is idle (the frame-silence rule). */
    fun setPage(number: Int, total: Int) {
        val text = pageIndicator.context.getString(R.string.sketch_page_indicator, number, total)
        PenIdle.whenIdle(paper, pageIndicator) { if (pageIndicator.text != text) pageIndicator.text = text }
    }

    companion object {
        /** The rubber's radius, in px — g-paper's rubbing eraser at its default lift. */
        const val ERASER_RADIUS_PX = 12f

        /** The stylus smudge's radius, in px — a blending stump three quarters of the finger's 32
         *  (SN: 16 → 24 on Greg's second walk, "too fine"). */
        const val SMUDGE_TOOL_RADIUS_PX = 24f
    }
}
