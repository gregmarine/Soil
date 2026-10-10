package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * The Heading button's menu (Greg, 2026-10-10): a bordered column of the six levels, H1 to H6,
 * hung under the button it came out of — the notebook's Insert bar's shape, not a sheet in the middle
 * of the screen.
 *
 * A [PopupWindow] rather than a view in the activity's layout because the document's root is a
 * column in flow, with nothing to float a bar in. It is **not focusable**: the editor keeps the
 * caret and the selection the pick acts on, and the keyboard stays where it is. A touch outside
 * takes it down and still lands where it was aimed.
 */
internal class HeadingMenu(private val context: Context, private val onPick: (level: Int) -> Unit) {

    private val popup: PopupWindow by lazy { build() }

    fun toggle(anchor: View) {
        if (popup.isShowing) popup.dismiss()
        else popup.showAsDropDown(anchor, 0, (GAP_DP * context.resources.displayMetrics.density).toInt())
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    private fun build(): PopupWindow {
        val pad = (4f * context.resources.displayMetrics.density).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, PaperR.drawable.shape_dialog_bordered)
            LEVELS.forEachIndexed { i, (icon, hint) ->
                addView(FormatBar.iconButton(context, icon, context.getString(hint)) { dismiss(); onPick(i + 1) })
            }
        }
        return PopupWindow(column, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            // A background is what lets an outside touch dismiss; the column paints its own border.
            setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
            elevation = 0f
        }
    }

    private companion object {
        /** The selection bar's gap, so every bar hung off a button sits off it alike. */
        const val GAP_DP = 8f

        /** The six levels' glyphs and hints, in order: index + 1 is the level. */
        val LEVELS = listOf(
            PaperR.drawable.ic_h_1 to R.string.fmt_h1,
            PaperR.drawable.ic_h_2 to R.string.fmt_h2,
            PaperR.drawable.ic_h_3 to R.string.fmt_h3,
            PaperR.drawable.ic_h_4 to R.string.fmt_h4,
            PaperR.drawable.ic_h_5 to R.string.fmt_h5,
            PaperR.drawable.ic_h_6 to R.string.fmt_h6,
        )
    }
}
