package com.symmetricalpalmtree.soil.tags

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * One tag row, built in code: the row count is the content. A hand-sized tap target, a label,
 * and a trailing glyph that says what tapping it does. No ripple, no elevation, a 1 dp hairline
 * in ink under each row.
 */
object TagRowView {

    fun rowHeightPx(context: Context): Int =
        context.resources.getDimensionPixelSize(PaperR.dimen.toolbar_button_size) + context.resources.displayMetrics.density.toInt().coerceAtLeast(1)

    fun build(
        context: Context,
        label: String,
        trailingIcon: Int?,
        trailingDescription: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
    ): View {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, PaperR.color.inkBlack)
        val hairline = d.toInt().coerceAtLeast(1)
        val iconSize = (24 * d).toInt()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            isClickable = true
            isFocusable = false   // a chrome row must never steal focus from the add field
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        row.addView(
            AppCompatTextView(context).apply {
                text = label
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (trailingIcon != null) {
            row.addView(
                AppCompatImageView(context).apply {
                    setImageResource(trailingIcon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = trailingDescription
                },
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
        }
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx(context) - hairline))
        column.addView(View(context).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline))
        return column
    }

    /** A Manage overview row is two lines, so it is taller. */
    fun targetRowHeightPx(context: Context): Int = rowHeightPx(context) + (20 * context.resources.displayMetrics.density).toInt()

    /** One row of the Manage overview: a target over the tags it carries. The second line is ink
     *  and smaller, never grey: it is the answer the row exists to give. */
    fun buildTarget(context: Context, label: String, tags: String, onClick: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, PaperR.color.inkBlack)
        val hairline = d.toInt().coerceAtLeast(1)
        val iconSize = (24 * d).toInt()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            isClickable = true
            isFocusable = false
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }
        val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lines.addView(AppCompatTextView(context).apply { text = label; textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(ink) })
        lines.addView(AppCompatTextView(context).apply { text = tags; textSize = 13f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(ink) })
        row.addView(lines, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            AppCompatImageView(context).apply {
                setImageResource(PaperR.drawable.ic_page_next)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            },
            LinearLayout.LayoutParams(iconSize, iconSize),
        )
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, targetRowHeightPx(context) - hairline))
        column.addView(View(context).apply { setBackgroundColor(ink) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline))
        return column
    }
}
