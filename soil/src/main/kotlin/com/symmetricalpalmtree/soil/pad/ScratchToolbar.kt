package com.symmetricalpalmtree.soil.pad

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec

/**
 * The pad's chrome: Back, the three tools and the title on the top bar; the page arrows on the
 * bottom one. The tool half is `:paper`'s [PaperToolbar], and this adds what is the pad's own:
 * the fixed tool values, the page arrows, and the page indicator behind the frame-silence gate.
 *
 * **The tools are fixed.** One black pen at [PEN_WIDTH_PX], eraser [ERASER_RADIUS_PX] — no width,
 * shade or style panels. The eraser has two kinds: a second tap on the armed eraser opens the
 * shared `EraserBar` (Point · Lasso) — the screen owns the bar, this just forwards the re-tap.
 * Smart lasso and scribble erase are armed by the screen before the listener attaches.
 *
 * **The arrows no-op at a bound, never disable.** A greyed control is invisible on e-ink, so the
 * buttons always look the same and simply do nothing at page 1 or page N.
 *
 * **The indicator waits for the pen.** Never present an app frame while [PaperView.isPenActive],
 * and this bar is the pad's only text that changes.
 */
class ScratchToolbar(
    private val paper: PaperView,
    bottomBar: View,
    btnBack: ImageButton,
    btnPen: ImageButton,
    btnEraser: ImageButton,
    btnLasso: ImageButton,
    private val btnPrevPage: ImageButton,
    private val btnNextPage: ImageButton,
    private val pageIndicator: TextView,
    /** Send the page to the notebook behind the pad: shown only when there is one. */
    btnSend: ImageButton,
    showSend: Boolean,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    /** A tap on the already-armed eraser: the screen toggles the eraser sub-bar. */
    onEraserReTap: () -> Unit,
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
            bar = bottomBar,
            btnBack = btnBack,
            btnPen = btnPen,
            btnEraser = btnEraser,
            btnLasso = btnLasso,
            paper = paper,
            onBack = onBack,
            onEraserReTap = onEraserReTap,
            onToolTapped = onToolTapped,
            onSynced = onSynced,
        )

        listOf(btnPrevPage, btnNextPage, btnSend).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnSend.visibility = if (showSend) View.VISIBLE else View.GONE
        btnSend.setOnClickListener { releaseRenderIfIdle(); onSend() }
        btnPrevPage.setOnClickListener { releaseRenderIfIdle(); onPrevPage() }
        btnNextPage.setOnClickListener { releaseRenderIfIdle(); onNextPage() }
        pageIndicator.text = ""
    }

    /** Make the tool buttons honest — driven from `PaperListener.onToolChanged`, never from a tap:
     *  smart lasso arms LASSO and restores PEN on its own. */
    fun sync(tool: Tool) = tools.sync(tool)

    /** Arm [tool] from our side and sync the buttons — what the eraser sub-bar's pick lands on. */
    fun arm(tool: Tool) = tools.arm(tool)

    /** `n / N`, presented only once the pen is idle (the frame-silence rule). */
    fun setPage(number: Int, total: Int) {
        val text = pageIndicator.context.getString(R.string.scratch_page_indicator, number, total)
        whenPenIdle { pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) = PenIdle.whenIdle(paper, pageIndicator, action)

    /** The [PaperView.releaseRender] contract: pen-gated, or a tap inside the pen-up tail can cost
     *  a live stroke. While the pen is active nobody is looking at a pressed state anyway. */
    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        /** The one pen width, in px — Notesprout's, so the two surfaces write identically. */
        const val PEN_WIDTH_PX = 3f

        /** The one eraser hit radius, in px — g-paper's default. */
        const val ERASER_RADIUS_PX = 15f
    }
}
