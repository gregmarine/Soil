package com.symmetricalpalmtree.soil.docsprout.editor

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.seam.SoilAddress
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * Where a link in the rendered document points. Two ways to say: **Choose from library**, which
 * hands over to Soil's own picker (a notebook, a page of one, another document), or an address
 * typed into the field. With a link already in place the dialog also offers to take it off.
 *
 * A link into the library is ids, which mean nothing to read: the field is left empty for one,
 * and a line above it says the link points into the library. Words that read as a Bible
 * A link into the Bible shows its reference in the field, and its Edit reads the field as a
 * reference; the tool never makes a Bible link from plain words (the pass does, and the
 * selection's Relink Bible puts a removed one back).
 * The IME is asked for on the way in and never hidden. An address is the writer's and is never
 * logged.
 */
internal object LinkDialog {

    fun ask(activity: Activity, current: String?, onChooseFromLibrary: () -> Unit, apply: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val intoLibrary = current != null && SoilAddress.isSoil(current)
        // A link into the Bible reads as its reference, which is what the field holds to edit.
        val reference = current?.let { BibleLinks.labelOf(it) }
        val input = AppCompatEditText(activity).apply {
            setHint(R.string.link_field_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
            background = ContextCompat.getDrawable(activity, PaperR.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            val shown = reference ?: current
            if (!shown.isNullOrEmpty() && !intoLibrary) { setText(shown); setSelection(0, shown.length) }
        }
        var dialog: AlertDialog? = null
        val choose = AppCompatButton(activity).apply {
            setText(R.string.link_choose_library)
            isAllCaps = false
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
            background = ContextCompat.getDrawable(activity, PaperR.drawable.shape_bordered)
            stateListAnimator = null
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setOnClickListener { dialog?.dismiss(); onChooseFromLibrary() }
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (16 * density).toInt(), side, 0)
            addView(choose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (16 * density).toInt() })
            addView(input)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.link_title)
            .setView(wrapper)
            .setPositiveButton(R.string.link_confirm) { _, _ ->
                val typed = input.text?.toString().orEmpty().trim()
                // Link pressed over an untouched library link changes nothing.
                if (typed.isNotEmpty() || !intoLibrary) apply(typed)
            }
            .setNegativeButton(PaperR.string.cancel, null)
        if (intoLibrary) builder.setMessage(R.string.link_into_library)
        if (current != null) builder.setNeutralButton(R.string.link_remove) { _, _ -> apply("") }
        dialog = Dialogs.style(builder.create())
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
