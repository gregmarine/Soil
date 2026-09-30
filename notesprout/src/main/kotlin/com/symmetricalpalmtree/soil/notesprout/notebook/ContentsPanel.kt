package com.symmetricalpalmtree.soil.notesprout.notebook

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.objects.OutlineTree

/**
 * **The Contents**, Notesprout SN's: a left sidebar, "Contents" over a rule, then the headings as a
 * tree, one row each: `[+ / −] [page number] | [label]`, indented by level. A node with children
 * folds and unfolds on its button; a tap on a row goes to its page; the row for the page showing
 * wears a bar at its right edge. Paged, never scrolled, with a first / prev / next / last pager.
 * A tap outside the panel closes it.
 */
class ContentsPanel(private val activity: Activity, private val onPick: (pageId: String) -> Unit) {

    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(roots: List<OutlineTree.Node>, currentPageIndex: Int, truncated: Boolean, onDismiss: () -> Unit) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        val ctx = activity
        val d = ctx.resources.displayMetrics
        val density = d.density
        val ink = ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val white = ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.paperWhite)
        val hairline = Math.round(density).coerceAtLeast(1)
        val barHeight = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_bar_thickness)
        val rowHeight = ((ROW_DP + 1) * density).toInt()
        val panelWidth = (d.widthPixels * WIDTH_FRACTION).toInt()
        val bodyHeight = d.heightPixels - 2 * barHeight - 2 * hairline - (8 * density).toInt()
        val perPage = (bodyHeight / rowHeight).coerceAtLeast(1)

        val all = OutlineTree.all(roots)
        val expanded = HashSet<String>()
        all.lastOrNull { it.pageIndex <= currentPageIndex }?.let { expanded += OutlineTree.ancestorsOf(it) }
        var page = 0

        val dlg = Dialog(ctx, com.symmetricalpalmtree.soil.paper.R.style.Theme_Soil)
        dialog = dlg
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = sidebar(ink, white, density)
            isClickable = true
        }

        // The header: the title over a rule.
        panel.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                addView(
                    AppCompatTextView(ctx).apply {
                        text = ctx.getString(R.string.contents_title)
                        textSize = 20f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ink)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight),
        )
        panel.addView(rule(ink, hairline))

        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
        }
        panel.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(rule(ink, hairline))
        val truncatedLine = AppCompatTextView(ctx).apply {
            text = ctx.getString(R.string.contents_truncated, OutlineTree.MAX_ENTRIES)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
            setTextColor(ink)
            visibility = if (truncated) View.VISIBLE else View.GONE
        }
        panel.addView(truncatedLine)

        val pageLabel = AppCompatTextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            minWidth = (56 * density).toInt()
            setTextColor(ink)
        }
        val pager = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }

        fun render() {
            rows.removeAllViews()
            val visible = OutlineTree.visible(roots, expanded)
            val highlight = OutlineTree.highlight(all, currentPageIndex, expanded)
            val pages = OutlineTree.pageCount(visible.size, perPage)
            page = page.coerceIn(0, pages - 1)
            val from = page * perPage
            for (node in visible.subList(from.coerceAtMost(visible.size), (from + perPage).coerceAtMost(visible.size))) {
                rows.addView(
                    row(node, node.id == highlight, node.id in expanded, ink, density, dlg) {
                        if (node.id in expanded) expanded.remove(node.id) else expanded.add(node.id)
                        render()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            pageLabel.text = ctx.getString(R.string.page_indicator, page + 1, pages)
            pager.visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        }
        fun turn(to: Int) {
            val pages = OutlineTree.pageCount(OutlineTree.visible(roots, expanded).size, perPage)
            val t = to.coerceIn(0, pages - 1)
            if (t == page) return
            page = t
            render()
        }
        pager.addView(toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_first, ctx.getString(R.string.cd_first_page)) { turn(0) })
        pager.addView(toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_prev, ctx.getString(R.string.cd_prev_page)) { turn(page - 1) })
        pager.addView(pageLabel)
        pager.addView(toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_next, ctx.getString(R.string.cd_next_page)) { turn(page + 1) })
        pager.addView(toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_last, ctx.getString(R.string.cd_last_page)) { turn(Int.MAX_VALUE) })
        panel.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))

        // Opens on the page that holds the current entry.
        val visibleNow = OutlineTree.visible(roots, expanded)
        val highlightNow = OutlineTree.highlight(all, currentPageIndex, expanded)
        page = OutlineTree.pageOf(visibleNow.indexOfFirst { it.id == highlightNow }, perPage)
        render()

        val root = FrameLayout(ctx).apply {
            setOnClickListener { dlg.dismiss() }
            addView(panel, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        }
        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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

    /** One row: `[+ / −] [page number] | [label]`, indented by level, a bar at the right edge when
     *  it is the current entry, a separator below. */
    private fun row(node: OutlineTree.Node, current: Boolean, expanded: Boolean, ink: Int, density: Float, dlg: Dialog, onToggle: () -> Unit): View {
        val ctx = activity
        val indent = ((node.level - 1).coerceAtLeast(0) * INDENT_DP * density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = if (current) activeBar(ink, density) else null
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (ROW_DP * density).toInt()
                    setPadding((4 * density).toInt() + indent, 0, (12 * density).toInt(), 0)
                    isClickable = true
                    isFocusable = true
                    contentDescription = node.label
                    setOnClickListener { dlg.dismiss(); onPick(node.pageId) }
                    // The toggle, or its space kept so the columns line up.
                    val toggle = toolButton(
                        if (expanded) com.symmetricalpalmtree.soil.paper.R.drawable.ic_minus else com.symmetricalpalmtree.soil.paper.R.drawable.ic_plus,
                        ctx.getString(if (expanded) R.string.contents_fold else R.string.contents_unfold),
                    ) { onToggle() }
                    if (node.children.isEmpty()) toggle.visibility = View.INVISIBLE
                    addView(toggle)
                    addView(
                        AppCompatTextView(ctx).apply {
                            text = (node.pageIndex + 1).toString()
                            textSize = 20f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            gravity = Gravity.CENTER
                            setTextColor(ink)
                        },
                        LinearLayout.LayoutParams((52 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                    addView(
                        View(ctx).apply { setBackgroundColor(ink) },
                        LinearLayout.LayoutParams(Math.round(density).coerceAtLeast(1), (36 * density).toInt()).apply {
                            marginStart = (4 * density).toInt(); marginEnd = (4 * density).toInt()
                        },
                    )
                    addView(
                        AppCompatTextView(ctx).apply {
                            text = node.label
                            textSize = 20f
                            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(ink)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                View(ctx).apply { setBackgroundColor(ink) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(density).coerceAtLeast(1)).apply {
                    marginStart = (16 * density).toInt(); marginEnd = (24 * density).toInt()
                },
            )
        }
    }

    /** The panel: white with a 2 dp rule on its right edge only. */
    private fun sidebar(ink: Int, white: Int, density: Float): LayerDrawable {
        val shape = GradientDrawable().apply {
            setColor(white)
            setStroke((2 * density).toInt(), ink)
        }
        val out = (4 * density).toInt()
        return LayerDrawable(arrayOf(shape)).apply { setLayerInset(0, -out, -out, 0, -out) }
    }

    /** The current row's mark: a 5 dp bar at its right edge. */
    private fun activeBar(ink: Int, density: Float): LayerDrawable {
        val bar = GradientDrawable().apply { setColor(ink) }
        return LayerDrawable(arrayOf(bar)).apply {
            setLayerGravity(0, Gravity.END)
            setLayerWidth(0, (5 * density).toInt())
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
            TooltipCompat.setTooltipText(this, hint)
            setOnClickListener { onClick() }
        }
    }

    private fun rule(ink: Int, thickness: Int): View = View(activity).apply {
        setBackgroundColor(ink)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness)
    }

    private companion object {
        const val WIDTH_FRACTION = 0.6f
        const val ROW_DP = 68
        const val INDENT_DP = 16
    }
}
