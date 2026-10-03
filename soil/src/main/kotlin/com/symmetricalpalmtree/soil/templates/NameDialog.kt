package com.symmetricalpalmtree.soil.templates

import android.app.Activity
import android.text.InputType
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.templates.TemplateNames

/**
 * The one "type a name" dialog of the paper library. The positive button is wired after
 * `show()`, so a refused name keeps the dialog open with the typing intact: [onAccept] decides
 * when to close, through the `dismiss` it is handed, since the duplicate check is a database
 * round trip only the caller can make. Re-entry is the caller's to guard. The IME is asked for
 * on the way in and never hidden.
 */
object NameDialog {

    fun show(activity: Activity, titleRes: Int, confirmRes: Int, initial: String = "", hintRes: Int = R.string.rename_hint, onCancel: () -> Unit = {}, onAccept: (name: String, dismiss: () -> Unit) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val d = activity.resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val input = AppCompatEditText(activity).apply {
            setHint(hintRes)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack))
            background = ContextCompat.getDrawable(activity, com.symmetricalpalmtree.soil.paper.R.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
            setSingleLine()
            if (initial.isNotEmpty()) { setText(initial); setSelection(0, initial.length) }
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * d).toInt()
            setPadding(side, (16 * d).toInt(), side, 0)
            addView(input)
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(wrapper)
                .setPositiveButton(confirmRes, null)
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // Cancel, Back and an outside touch are one answer; an accept that dismisses is not.
        var accepted = false
        dialog.setOnDismissListener { if (!accepted) onCancel() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onAccept(input.text?.toString().orEmpty().trim()) { accepted = true; dialog.dismiss() }
        }
        input.requestFocus()
    }

    /** The sentence a refused name gets. */
    fun problemMessage(activity: Activity, problem: TemplateNames.Problem): String = activity.getString(
        when (problem) {
            TemplateNames.Problem.EMPTY -> R.string.name_empty
            TemplateNames.Problem.RESERVED -> R.string.name_reserved
            TemplateNames.Problem.CHARSET -> R.string.name_charset
            TemplateNames.Problem.TOO_LONG -> R.string.name_too_long
        },
    )

    /** The name rules with their dialogs: the charset first, then the reserved root name. True when refused. */
    fun reject(activity: Activity, name: String, parentId: String): Boolean {
        TemplateNames.validate(name)?.let { problem ->
            Dialogs.problem(activity, R.string.name_problem_title, problemMessage(activity, problem))
            return true
        }
        if (com.symmetricalpalmtree.soil.paper.templates.TemplateIds.isReservedName(parentId, name)) {
            Dialogs.problem(activity, R.string.name_problem_title, activity.getString(R.string.template_name_reserved, com.symmetricalpalmtree.soil.paper.templates.TemplateIds.RESERVED_ROOT_NAME))
            return true
        }
        return false
    }
}
