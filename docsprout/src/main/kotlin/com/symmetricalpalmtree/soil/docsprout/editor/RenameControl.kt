package com.symmetricalpalmtree.soil.docsprout.editor

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.seam.SeamLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * Renaming a document from its own title. The document has no library card on this screen, so
 * the title is where its name is changed: it answers a tap, with a long-press hint saying so, and
 * nothing more: a boxed or underlined title would read as a field rather than a heading.
 *
 * **Soil is the only judge of a name.** This side refuses two things, and neither is worth a
 * dialog: blank, and the name it already has. Everything else goes to Soil.
 *
 * The positive button is wired **after** `show()`: the default listener dismisses before anything
 * can object, which would throw the typing away on every refusal. The IME is asked for on the way
 * in and never hidden. The name is the writer's and is never logged.
 */
internal class RenameControl(
    private val activity: Activity,
    private val binding: ActivityDocumentBinding,
    /** The screen's lifecycle scope: a rename that outlives the screen has nothing to redraw. */
    private val scope: CoroutineScope,
    /** Ask Soil to rename the open document. **Blocking**, run off Main. */
    private val rename: (String) -> Unit,
) {

    /** One rename in flight at a time: on e-ink an unguarded button gets double-tapped. */
    private var accepting = false

    fun install() {
        binding.title.setOnClickListener { if (!accepting && !activity.isFinishing) prompt() }
        TooltipCompat.setTooltipText(binding.title, activity.getString(R.string.rename_hint))
    }

    private fun titleNow(): String = binding.title.text?.toString().orEmpty()

    private fun prompt() {
        if (activity.isFinishing || activity.isDestroyed) return
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val current = titleNow()
        val input = AppCompatEditText(activity).apply {
            setHint(R.string.rename_field_hint)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, PaperR.color.inkBlack))
            background = ContextCompat.getDrawable(activity, PaperR.drawable.shape_bordered)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(SeamLimits.MAX_NAME_CHARS))
            if (current.isNotEmpty()) { setText(current); setSelection(0, current.length) }
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (16 * density).toInt(), side, 0)
            addView(input)
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.rename_title)
                .setView(wrapper)
                .setPositiveButton(R.string.rename_confirm, null)
                .setNegativeButton(PaperR.string.cancel, null)
                .create(),
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (accepting) return@setOnClickListener
            val typed = input.text?.toString().orEmpty().replace(Regex("\\s+"), " ").trim()
            // Blank, or the name it already has. Neither is a failure; neither is worth a word.
            if (typed.isEmpty() || typed == titleNow()) { dialog.dismiss(); return@setOnClickListener }
            submit(typed) { dialog.dismiss() }
        }
        input.requestFocus()
    }

    private fun submit(name: String, dismiss: () -> Unit) {
        accepting = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { rename(name) }.exceptionOrNull() }
            accepting = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (error == null) {
                binding.title.text = name
                dismiss()
            } else {
                // The class name only: a message could carry the name.
                Log.w(TAG, "rename failed: ${error.javaClass.simpleName}")
                Dialogs.problem(activity, R.string.rename_problem_title, R.string.rename_failed_body)
            }
        }
    }

    private companion object {
        const val TAG = "DocumentRename"
    }
}
