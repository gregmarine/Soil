package com.symmetricalpalmtree.soil.sketchsprout.sketch

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.RasterRubbing
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.sketchsprout.R

/**
 * The sketch screen's chrome: Back, the pencil and the rubber on the top bar (the gel pen, Smudge
 * and Guides join in their phases), the sketchbook's name between the groups; the page indicator
 * on the bottom bar. The tool half is `:paper`'s [PaperToolbar] — with **no lasso button**, which
 * is what `btnLasso`'s nullability is for: a raster page has no objects to select.
 *
 * - **A graphite pencil of one width** ([SketchPalette.PENCIL_WIDTH_PX]) at the shade [state]
 *   holds. Both pen kinds are [Tool.PEN] to the engine; what differs is only what the engine is
 *   armed with, which is what [SketchToolState] holds and this class assigns.
 * - **The rubbing eraser** at [ERASER_RADIUS_PX] on g-paper's [RasterRubbing] defaults: within the
 *   radius the alpha is lifted a fraction per pass, so a light pass softens a line and a few firm
 *   passes take it out. No sub-bar — there are no strokes to lasso — and never remembered.
 * - **No pen gestures.** `smartLassoEnabled` and `scribbleEraseEnabled` are both off, set by the
 *   screen before the listener attaches: a hatch is not a scribble and a closed shading loop is
 *   not a selection.
 *
 * **The indicator and the title wait for the pen.** Never present an app frame while
 * [PaperView.isPenActive]; this bar's text is the screen's only text that changes.
 */
class SketchToolbar(
    private val paper: PaperView,
    topBar: View,
    btnBack: ImageButton,
    btnPencil: ImageButton,
    btnEraser: ImageButton,
    private val title: TextView,
    private val pageIndicator: TextView,
    onBack: () -> Unit,
    /** Any actual tool change — the screen takes down anything that belonged to the old tool. */
    onToolTapped: () -> Unit,
    /** After every sync — the collapsed chrome's corner button repaints from here. */
    onSynced: () -> Unit = {},
) {

    /** What the tools are set to. It lives here because the *engine* cannot hold it. */
    private var toolState: SketchToolState = SketchToolState.DEFAULT

    /** The armed tools, for the bars that paint from them. */
    val state: SketchToolState get() = toolState

    private val tools: PaperToolbar

    init {
        paper.tool = Tool.PEN
        paper.eraserRadius = ERASER_RADIUS_PX
        paper.rasterRubbing = RasterRubbing()
        // The defaults, until the screen restores what this device remembers (phase 2). Assigned
        // directly rather than through [apply], which syncs a toolbar that does not exist yet.
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
        )
        pageIndicator.text = ""
    }

    /** Arm [state] — the one door every tool choice comes through. It syncs the bar as well as the
     *  engine, because which pen button reads as armed follows the *kind* and the tool may not have
     *  moved at all. */
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
    }
}
