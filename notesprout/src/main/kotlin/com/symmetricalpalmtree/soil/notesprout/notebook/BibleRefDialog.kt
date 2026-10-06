package com.symmetricalpalmtree.soil.notesprout.notebook

import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * Say which passage: one field, Link, Cancel, [ObjectDialogs]' recipe to the line. Three
 * callers, one shape: the lasso bar's Bible (prefilled with what the recogniser read), the
 * Insert bar's (empty), and the Edit of a lone Bible link (the wrapped text's own words).
 *
 * **The words shown are the user's own, and stay so**: what is typed here is what the page will
 * read. The reference underneath is what those words parse to, and the caller never rewrites
 * the field with the canonical form.
 *
 * **A blank Link is a Cancel here**, the one difference from the heading and text dialogs,
 * where blank means delete: a Bible link's text is its reference, and no words is not a
 * reference to anything. Unlink is how a reference stops being a link; Delete is how it leaves
 * the page.
 *
 * **The IME is never hidden.** On the Supernote a hardware keyboard delivers keys only while
 * the IME is shown; the only soft-input call here asks for it, on the way in.
 *
 * With [offerVerses] an **"Insert the verses"** pill sits under the field, off by default: on,
 * the caller lands the passage's words instead of the words typed. Nothing here touches the
 * store, and nothing typed is ever logged.
 */
object BibleRefDialog {

    fun show(activity: AppCompatActivity, initial: String, onSave: (String) -> Unit) =
        show(activity, initial, offerVerses = false) { typed, _ -> onSave(typed) }

    fun show(activity: AppCompatActivity, initial: String, offerVerses: Boolean, onSave: (typed: String, verses: Boolean) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val input = AppCompatEditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
            setHintTextColor(ContextCompat.getColor(activity, PaperR.color.inkLight))
            setHint(R.string.bible_reference_hint)
            background = ContextCompat.getDrawable(activity, PaperR.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine()
        }
        // The pill: a checkbox with no button and the two-state pill as its background.
        val verses = AppCompatCheckBox(activity).apply {
            buttonDrawable = null
            background = ContextCompat.getDrawable(activity, PaperR.drawable.toggle_pill)
            stateListAnimator = null
            isChecked = false
            contentDescription = activity.getString(R.string.bible_verses_switch)
        }
        val versesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
            addView(
                AppCompatTextView(activity).apply {
                    text = activity.getString(R.string.bible_verses_switch)
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
                    setOnClickListener { verses.toggle() }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(verses, LinearLayout.LayoutParams((56 * d).toInt(), activity.resources.getDimensionPixelSize(PaperR.dimen.toolbar_button_size)))
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
            if (offerVerses) addView(versesRow)
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bible_reference_title)
                .setView(wrapper)
                .setPositiveButton(R.string.bible_reference_save) { _, _ ->
                    val typed = input.text?.toString()?.trim().orEmpty()
                    if (typed.isNotEmpty()) onSave(typed, offerVerses && verses.isChecked)
                }
                .setNegativeButton(PaperR.string.cancel, null)
                .create(),
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
