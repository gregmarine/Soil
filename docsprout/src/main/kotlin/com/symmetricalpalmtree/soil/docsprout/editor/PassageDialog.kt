package com.symmetricalpalmtree.soil.docsprout.editor

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.R as PaperR

/** Which passage to insert: one field, Insert, Cancel, [LinkDialog]'s recipe. The IME is asked
 *  for on the way in and never hidden. Nothing typed is logged. */
internal object PassageDialog {

    fun ask(activity: Activity, apply: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val input = AppCompatEditText(activity).apply {
            setHint(R.string.passage_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
            setHintTextColor(ContextCompat.getColor(activity, PaperR.color.inkLight))
            background = ContextCompat.getDrawable(activity, PaperR.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (16 * density).toInt(), side, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.passage_title)
                .setView(wrapper)
                .setPositiveButton(R.string.passage_insert) { _, _ ->
                    val typed = input.text?.toString().orEmpty().trim()
                    if (typed.isNotEmpty()) apply(typed)
                }
                .setNegativeButton(PaperR.string.cancel, null)
                .create(),
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
