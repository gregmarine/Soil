package com.symmetricalpalmtree.soil.notesprout.notebook

import android.app.Activity
import android.app.Dialog
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.objects.OutlineTree

/**
 * **The Contents**: the notebook's headings as a tree, a panel that comes in from the left. A
 * node with children folds and unfolds on its chevron; a tap on a row goes to its page; the
 * entry for the page showing is marked. Paged, never scrolled; a tap outside closes it.
 */
class ContentsPanel(private val activity: Activity, private val onPick: (pageId: String) -> Unit) {

    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(roots: List<OutlineTree.Node>, currentPageIndex: Int, truncated: Boolean, onDismiss: () -> Unit) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        val ctx = activity
        val d = ctx.resources.displayMetrics
        val ink = ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val hairline = Math.round(d.density).coerceAtLeast(1)
        val rowHeight = (ROW_DP * d.density).toInt()
        val barHeight = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_bar_thickness)
        val pad = (16 * d.density).toInt()
        val indent = (INDENT_DP * d.density).toInt()
        val iconSize = (24 * d.density).toInt()
        val panelWidth = (d.widthPixels * WIDTH_FRACTION).toInt()
        val perPage = ((d.heightPixels - barHeight - hairline) / rowHeight).coerceAtLeast(1)

        val all = OutlineTree.all(roots)
        // Open on the entry for this page, its ancestors unfolded.
        val expanded = HashSet<String>()
        val current = all.lastOrNull { it.pageIndex <= currentPageIndex }
        if (current != null) expanded += OutlineTree.ancestorsOf(current)
        var page = 0

        val dlg = Dialog(ctx, com.symmetricalpalmtree.soil.paper.R.style.Theme_Soil)
        dialog = dlg
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.paperWhite))
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pageText = AppCompatTextView(ctx).apply {
            textSize = 16f
            setTextColor(ink)
            gravity = Gravity.CENTER
            minWidth = (72 * d.density).toInt()
        }
        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        fun render() {
            body.removeAllViews()
            val visible = OutlineTree.visible(roots, expanded)
            val highlight = OutlineTree.highlight(all, currentPageIndex, expanded)
            val pages = OutlineTree.pageCount(visible.size, perPage)
            page = page.coerceIn(0, pages - 1)
            val from = page * perPage
            for (node in visible.subList(from.coerceAtMost(visible.size), (from + perPage).coerceAtMost(visible.size))) {
                body.addView(row(node, node.id == highlight, node.id in expanded, ink, pad, indent, iconSize, dlg) {
                    if (node.id in expanded) expanded.remove(node.id) else expanded.add(node.id)
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight - hairline))
                body.addView(rule(ink, hairline))
            }
            if (truncated && page == pages - 1) {
                body.addView(
                    AppCompatTextView(ctx).apply {
                        text = ctx.getString(R.string.contents_truncated, OutlineTree.MAX_ENTRIES)
                        textSize = 13f
                        setTextColor(ink)
                        setPadding(pad, pad / 2, pad, pad / 2)
                    },
                )
            }
            pageText.text = ctx.getString(R.string.page_indicator, page + 1, pages)
            footer.visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        }
        footer.addView(toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_prev, ctx.getString(R.string.cd_prev_page)) { page--; render() })
        footer.addView(pageText)
        footer.addView(toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_next, ctx.getString(R.string.cd_next_page)) { page++; render() })
        panel.addView(rule(ink, hairline))
        panel.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))
        // The page holding the current entry is the one it opens on.
        val visibleNow = OutlineTree.visible(roots, expanded)
        val highlightNow = OutlineTree.highlight(all, currentPageIndex, expanded)
        page = OutlineTree.pageOf(visibleNow.indexOfFirst { it.id == highlightNow }, perPage)
        render()

        val root = FrameLayout(ctx).apply {
            setOnClickListener { dlg.dismiss() }
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isClickable = true
                    addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    addView(rule(ink, 2 * hairline, vertical = true))
                },
                FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START),
            )
        }
        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setDimAmount(0f)
        }
        dlg.setOnDismissListener { dialog = null; onDismiss() }
        dlg.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun row(node: OutlineTree.Node, current: Boolean, expanded: Boolean, ink: Int, pad: Int, indent: Int, iconSize: Int, dlg: Dialog, onToggle: () -> Unit): View {
        val ctx = activity
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad + indent * (node.level - 1), 0, pad / 2, 0)
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            isSelected = current
            isClickable = true
            isFocusable = true
            contentDescription = node.label
            setOnClickListener { dlg.dismiss(); onPick(node.pageId) }
            addView(
                AppCompatTextView(ctx).apply {
                    text = node.label
                    textSize = if (node.level <= 2) 18f else 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ink)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                AppCompatTextView(ctx).apply {
                    text = (node.pageIndex + 1).toString()
                    textSize = 14f
                    setTextColor(ink)
                    setPadding(pad / 2, 0, pad / 2, 0)
                },
            )
            // The chevron folds and unfolds; a row without children keeps the column.
            val chevron = if (node.children.isEmpty()) View(ctx) else AppCompatImageView(ctx).apply {
                setImageResource(if (expanded) com.symmetricalpalmtree.soil.paper.R.drawable.ic_minus else com.symmetricalpalmtree.soil.paper.R.drawable.ic_plus)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = ctx.getString(if (expanded) R.string.contents_fold else R.string.contents_unfold)
                isClickable = true
                setOnClickListener { onToggle() }
            }
            addView(chevron, LinearLayout.LayoutParams(iconSize * 2, iconSize * 2))
        }
    }

    private fun toolButton(iconRes: Int, hint: String, onClick: () -> Unit): View {
        val ctx = activity
        val size = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_button_size)
        val padding = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_button_padding)
        return AppCompatImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.FIT_CENTER
            stateListAnimator = null
            setImageResource(iconRes)
            contentDescription = hint
            setOnClickListener { onClick() }
        }
    }

    private fun rule(ink: Int, thickness: Int, vertical: Boolean = false): View = View(activity).apply {
        setBackgroundColor(ink)
        layoutParams = if (vertical) LinearLayout.LayoutParams(thickness, ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness)
    }

    private companion object {
        const val WIDTH_FRACTION = 0.6f
        const val ROW_DP = 68
        const val INDENT_DP = 16
    }
}
