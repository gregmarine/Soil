package com.symmetricalpalmtree.soil.export

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
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

    fun toggle(text: String, on: Boolean, onToggle: () -> Unit): View =
        AppCompatCheckBox(android.view.ContextThemeWrapper(context, com.symmetricalpalmtree.soil.paper.R.style.Widget_Soil_Toggle), null, 0).apply {
            this.text = text
            textSize = 16f
            setTextColor(ink)
            background = ColorDrawable(Color.TRANSPARENT)
            stateListAnimator = null
            isChecked = on
            setOnClickListener { onToggle() }
            setPadding(padV, padV, padV, padV)
            layoutParams = wrapRow()
        }

    private fun wrapRow() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
