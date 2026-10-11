package com.symmetricalpalmtree.soil.export

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.R

/** The export screen's rows, in Soil's own widgets: a caption, a value, a radio, a tick. */
class ExportPanel(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
    private val padV = (8 * density).toInt()

    fun caption(text: String): TextView = AppCompatTextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(ink)
        setPadding(0, (12 * density).toInt(), 0, 0)
        layoutParams = wrapRow()
    }

    fun value(text: String): TextView = AppCompatTextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(ink)
        setPadding(0, padV, 0, padV)
        layoutParams = wrapRow()
    }

    fun choice(text: String, checked: Boolean, onLongPress: (() -> Unit)? = null, onPick: () -> Unit): View =
        AppCompatRadioButton(android.view.ContextThemeWrapper(context, com.symmetricalpalmtree.soil.paper.R.style.Widget_Soil_RadioButton), null, 0).apply {
            this.text = text
            textSize = 16f
            setTextColor(ink)
            background = ColorDrawable(Color.TRANSPARENT)
            stateListAnimator = null
            isChecked = checked
            setOnClickListener { onPick() }
            if (onLongPress != null) setOnLongClickListener { onLongPress(); true }
            setPadding(padV, padV, padV, padV)
            layoutParams = wrapRow()
        }

    /** A tick that shows or hides beside its label, the whole row the target: a box drawn by
     *  the platform is not an e-ink shape. */
    fun toggle(text: String, on: Boolean, onToggle: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(padV, padV, padV, padV)
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { onToggle() }
        val iconSize = (24 * density).toInt()
        addView(
            AppCompatImageView(context).apply {
                setImageResource(com.symmetricalpalmtree.soil.paper.R.drawable.ic_check)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = if (on) View.VISIBLE else View.INVISIBLE
                contentDescription = null
            },
            LinearLayout.LayoutParams(iconSize, iconSize).also { it.marginEnd = (12 * density).toInt() },
        )
        addView(AppCompatTextView(context).apply { this.text = text; textSize = 16f; setTextColor(ink) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = wrapRow()
    }

    /** A door: a value with an icon, the whole row the target, opening a chooser of its own. */
    fun door(iconRes: Int, text: String, onTap: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(padV, padV, padV, padV)
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { onTap() }
        val iconSize = (24 * density).toInt()
        addView(
            AppCompatImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            },
            LinearLayout.LayoutParams(iconSize, iconSize).also { it.marginEnd = (12 * density).toInt() },
        )
        addView(AppCompatTextView(context).apply { this.text = text; textSize = 16f; setTextColor(ink) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layoutParams = wrapRow()
    }

    private fun wrapRow() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
