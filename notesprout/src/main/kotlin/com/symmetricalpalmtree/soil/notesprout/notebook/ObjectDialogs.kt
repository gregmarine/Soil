package com.symmetricalpalmtree.soil.notesprout.notebook

import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.objects.TextLines
import com.symmetricalpalmtree.soil.paper.core.Dialogs

/**
 * The two dialogs a typed object comes through. **The IME is never hidden**: on the Supernote a
 * hardware keyboard delivers keys only while the IME is shown, so the one soft-input call asks for
 * it, on the way in.
 *
 * **An empty Save is a real answer**: clearing the words is how an object is taken back off the
 * page. Cancel, Back and an outside touch are one answer, [onCancel].
 */
object ObjectDialogs {

    /** One line: a heading's words, hash-free. The level lives in its own column. */
    fun heading(activity: AppCompatActivity, initial: String, onSave: (String) -> Unit, onCancel: () -> Unit = {}) =
        show(activity, R.string.heading_edit_title, initial, singleLine = true, onSave = { onSave(it.trim()) }, onCancel = onCancel)

    /** Several lines: a text object's raw Markdown, shown as it is stored. Enter is a newline. */
    fun text(activity: AppCompatActivity, initial: String, onSave: (String) -> Unit, onCancel: () -> Unit = {}) =
        show(activity, R.string.text_edit_title, initial, singleLine = false, onSave = { onSave(TextLines.typed(it)) }, onCancel = onCancel)

    /** One line: a name for a new notebook. A blank answer is a cancel: a notebook has a name. */
    fun name(activity: AppCompatActivity, titleRes: Int, initial: String, onSave: (String) -> Unit, onCancel: () -> Unit = {}) =
        show(activity, titleRes, initial, singleLine = true, onSave = { val n = it.trim(); if (n.isEmpty()) onCancel() else onSave(n) }, onCancel = onCancel)

    private fun show(activity: AppCompatActivity, titleRes: Int, initial: String, singleLine: Boolean, onSave: (String) -> Unit, onCancel: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val input = AppCompatEditText(activity).apply {
            setText(initial)
            // The caret at the end, nothing selected: the person came to fix a word, not retype.
            setSelection(initial.length)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack))
            background = ContextCompat.getDrawable(activity, com.symmetricalpalmtree.soil.paper.R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            if (singleLine) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 1
                setSingleLine()
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 4
                maxLines = 12
                gravity = Gravity.TOP or Gravity.START
                isVerticalScrollBarEnabled = true
            }
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
        }
        var answered = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(wrapper)
                .setPositiveButton(R.string.object_save) { _, _ -> answered = true; onSave(input.text?.toString().orEmpty()) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel) { _, _ -> answered = true; onCancel() }
                .create(),
        )
        dialog.setOnCancelListener { if (!answered) { answered = true; onCancel() } }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
