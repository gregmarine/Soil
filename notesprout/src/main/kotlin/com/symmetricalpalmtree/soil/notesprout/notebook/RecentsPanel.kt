package com.symmetricalpalmtree.soil.notesprout.notebook

import android.app.Activity
import android.app.Dialog
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.seam.SeamItem
import java.util.Date

/**
 * **The Recents**: the notebooks opened most recently, as a panel that comes in from the right
 * edge, and switches to the one tapped. The list is Soil's: the index keeps when each item was
 * last opened. Nothing here is stored.
 */
class RecentsPanel(activity: Activity, onPick: (SeamItem) -> Unit) : EdgeListPanel<SeamItem>(
    activity,
    emptyRes = R.string.recents_empty,
    rowTitle = { it.name },
    rowDetail = {
        val at = Date(it.updatedAt)
        activity.getString(R.string.recents_row_detail, DateFormat.getMediumDateFormat(activity).format(at), DateFormat.getTimeFormat(activity).format(at))
    },
    onPick = onPick,
)

/**
 * A list as a panel that comes in from the right edge: one row per item, each something to
 * open, and a tap on one opens it. No title and no close button: a tap outside the panel closes
 * it. The rows are built before the panel shows and paged, never scrolled.
 */
open class EdgeListPanel<T>(
    private val activity: Activity,
    private val emptyRes: Int,
    private val rowTitle: (T) -> String,
    private val rowDetail: (T) -> String,
    private val onPick: (T) -> Unit,
) {
    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(items: List<T>, onDismiss: () -> Unit) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        val ctx = activity
        val d = ctx.resources.displayMetrics
        val ink = ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val hairline = Math.round(d.density).coerceAtLeast(1)
        val rowHeight = (ROW_DP * d.density).toInt()
        val barHeight = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_bar_thickness)
        val pad = (16 * d.density).toInt()
        val panelWidth = (d.widthPixels * WIDTH_FRACTION).toInt()
        val perPage = ((d.heightPixels - barHeight - hairline) / rowHeight).coerceAtLeast(1)
        val pages = if (items.isEmpty()) 1 else (items.size + perPage - 1) / perPage
        var page = 0

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.paperWhite))
        }
        val dlg = Dialog(ctx, com.symmetricalpalmtree.soil.paper.R.style.Theme_Soil)
        dialog = dlg

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val pageText = AppCompatTextView(ctx).apply {
            textSize = 16f
            setTextColor(ink)
            gravity = Gravity.CENTER
            minWidth = (72 * d.density).toInt()
        }
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        }
        fun render() {
            body.removeAllViews()
            if (items.isEmpty()) {
                body.addView(
                    AppCompatTextView(ctx).apply {
                        text = ctx.getString(emptyRes)
                        textSize = 16f
                        setTextColor(ink)
                        setPadding(pad, pad, pad, pad)
                    },
                )
            }
            val from = page * perPage
            for (item in items.subList(from.coerceAtMost(items.size), (from + perPage).coerceAtMost(items.size))) {
                body.addView(row(item, ink, pad, dlg), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight - hairline))
                body.addView(rule(ink, hairline))
            }
            pageText.text = ctx.getString(R.string.page_indicator, page + 1, pages)
        }
        fun turn(to: Int) {
            val t = to.coerceIn(0, pages - 1)
            if (t == page) return
            page = t
            render()
        }
        footer.addView(
            toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_prev, ctx.getString(R.string.cd_prev_page)) { turn(page - 1) },
        )
        footer.addView(pageText)
        footer.addView(
            toolButton(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_next, ctx.getString(R.string.cd_next_page)) { turn(page + 1) },
        )
        panel.addView(rule(ink, hairline))
        panel.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight))
        render()

        // The panel at the end edge, its rule on the left; a tap on the scrim closes it.
        val root = FrameLayout(ctx).apply {
            setOnClickListener { dlg.dismiss() }
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isClickable = true
                    addView(rule(ink, 2 * hairline, vertical = true))
                    addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                },
                FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
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

    private fun row(item: T, ink: Int, pad: Int, dlg: Dialog): View {
        val ctx = activity
        val title = rowTitle(item)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { dlg.dismiss(); onPick(item) }
            addView(
                AppCompatTextView(ctx).apply {
                    text = title
                    textSize = 18f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ink)
                },
            )
            addView(
                AppCompatTextView(ctx).apply {
                    text = rowDetail(item)
                    textSize = 13f
                    maxLines = 1
                    setTextColor(ink)
                },
            )
        }
    }

    /** A toolbar button as the style makes one: the style cannot be applied to a view built in code. */
    private fun toolButton(iconRes: Int, hint: String, onClick: () -> Unit): View {
        val ctx = activity
        val size = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_button_size)
        val padding = ctx.resources.getDimensionPixelSize(com.symmetricalpalmtree.soil.paper.R.dimen.toolbar_button_padding)
        return AppCompatImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
            setPadding(padding, padding, padding, padding)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
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
        const val WIDTH_FRACTION = 0.5f
        const val ROW_DP = 72
    }
}
