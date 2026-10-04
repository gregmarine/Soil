package com.symmetricalpalmtree.soil.docsprout.editor

import android.app.Activity
import android.text.InputType
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * Where a link in the rendered document points: one field for the address. With a link already
 * in place the field holds its address and the dialog also offers to take the link off. The IME
 * is asked for on the way in and never hidden. The address is the writer's and is never logged.
 */
internal object LinkDialog {

    fun ask(activity: Activity, current: String?, apply: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val input = AppCompatEditText(activity).apply {
            setHint(R.string.link_field_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
            background = ContextCompat.getDrawable(activity, PaperR.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            if (!current.isNullOrEmpty()) { setText(current); setSelection(0, current.length) }
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (16 * density).toInt(), side, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.link_title)
            .setView(wrapper)
            .setPositiveButton(R.string.link_confirm) { _, _ -> apply(input.text?.toString().orEmpty().trim()) }
            .setNegativeButton(PaperR.string.cancel, null)
        if (current != null) builder.setNeutralButton(R.string.link_remove) { _, _ -> apply("") }
        val dialog = Dialogs.style(builder.create())
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
