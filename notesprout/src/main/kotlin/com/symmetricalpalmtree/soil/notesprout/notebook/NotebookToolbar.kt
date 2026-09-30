package com.symmetricalpalmtree.soil.notesprout.notebook

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.paper.core.InkTones

/**
 * The notebook's chrome: Close, the three tools and the name on the top bar; the page arrows and
 * the page indicator on the bottom one. The tool half is `:paper`'s [PaperToolbar].
 *
 * **The pen has one width and one of sixteen shades.** The shade is a level on [InkTones]'
 * ladder, remembered device-wide; [setPenLevel] arms it. The eraser has two kinds: a second tap
 * on the armed eraser opens the shared `EraserBar`.
 *
 * **The arrows no-op at a bound, never disable.** A greyed control is invisible on e-ink.
 *
 * **The text waits for the pen.** Never present an app frame while [PaperView.isPenActive].
 */
class NotebookToolbar(
    private val paper: PaperView,
    bottomBar: View,
    btnBack: ImageButton,
    btnPen: ImageButton,
    btnEraser: ImageButton,
    btnLasso: ImageButton,
    private val btnPrevPage: ImageButton,
    private val btnNextPage: ImageButton,
    private val title: TextView,
    private val pageIndicator: TextView,
    penLevel: Int,
    onBack: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onEraserReTap: () -> Unit,
    onToolTapped: () -> Unit,
    onSynced: () -> Unit = {},
) {

    private val tools: PaperToolbar

    init {
        paper.tool = Tool.PEN
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX
        setPenLevel(penLevel)

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

        listOf(btnPrevPage, btnNextPage).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
        btnPrevPage.setOnClickListener { PenIdle.releaseRenderIfIdle(paper); onPrevPage() }
        btnNextPage.setOnClickListener { PenIdle.releaseRenderIfIdle(paper); onNextPage() }
        pageIndicator.text = ""
        title.text = ""
    }

    fun sync(tool: Tool) = tools.sync(tool)

    fun arm(tool: Tool) = tools.arm(tool)

    /** Arm the pen with the shade [level] names. */
    fun setPenLevel(level: Int) {
        paper.penColor = InkTones.tone(level)
    }

    fun setTitle(name: String) = PenIdle.whenIdle(paper, title) { title.text = name }

    /** `n / N`, presented only once the pen is idle. */
    fun setPage(number: Int, total: Int) {
        val text = pageIndicator.context.getString(R.string.page_indicator, number, total)
        PenIdle.whenIdle(paper, pageIndicator) { pageIndicator.text = text }
    }

    companion object {
        /** The one pen width, in px: the Scratch Pad's too, so the two surfaces write identically. */
        const val PEN_WIDTH_PX = 3f

        /** The eraser's hit radius, in px: g-paper's default. */
        const val ERASER_RADIUS_PX = 15f
    }
}
