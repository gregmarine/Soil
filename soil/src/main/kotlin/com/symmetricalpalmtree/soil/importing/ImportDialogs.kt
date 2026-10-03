package com.symmetricalpalmtree.soil.importing

import android.app.Activity
import android.text.InputType
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The import's questions, each a suspending dialog that answers null for Cancel. */
object ImportDialogs {

    enum class Choice { PRIMARY, SECONDARY }

    suspend fun choose(activity: Activity, @StringRes titleRes: Int, message: CharSequence, @StringRes primaryRes: Int, @StringRes secondaryRes: Int): Choice? =
        suspendCancellableCoroutine { cont ->
            if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
            var answer: Choice? = null
            val dialog = Dialogs.style(
                AlertDialog.Builder(activity).setTitle(titleRes).setMessage(message)
                    .setPositiveButton(primaryRes) { _, _ -> answer = Choice.PRIMARY }
                    .setNeutralButton(secondaryRes) { _, _ -> answer = Choice.SECONDARY }
                    .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                    .create(),
            )
            dialog.setOnDismissListener { if (cont.isActive) cont.resume(answer) }
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
        }

    suspend fun pickFromList(activity: Activity, @StringRes titleRes: Int, labels: List<String>): Int? = suspendCancellableCoroutine { cont ->
        if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
        var chosen: Int? = null
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity).setTitle(titleRes).setItems(labels.toTypedArray()) { _, which -> chosen = which }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        )
        dialog.setOnDismissListener { if (cont.isActive) cont.resume(chosen) }
        cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        dialog.show()
    }

    /** A passphrase typed for a foreign file. Never saved in the field's state. */
    suspend fun passphrase(activity: Activity, @StringRes titleRes: Int, @StringRes bodyRes: Int, @StringRes errorRes: Int? = null): String? =
        suspendCancellableCoroutine { cont ->
            if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
            val d = activity.resources.displayMetrics.density
            val ink = ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
            val field = AppCompatEditText(activity).apply {
                setHint(R.string.import_passphrase_hint)
                setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.shape_bordered)
                setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                setTextColor(ink)
                setHintTextColor(ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkLight))
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
                maxLines = 1
                isSingleLine = true
            }
            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val side = (24 * d).toInt()
                setPadding(side, (16 * d).toInt(), side, 0)
                addView(TextView(activity).apply { setText(bodyRes); setTextColor(ink); textSize = 14f })
                errorRes?.let { res -> addView(TextView(activity).apply { setText(res); setTextColor(ink); textSize = 14f; setPadding(0, (8 * d).toInt(), 0, 0) }) }
                addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (12 * d).toInt() })
            }
            var typed: String? = null
            val dialog = Dialogs.style(
                AlertDialog.Builder(activity).setTitle(titleRes).setView(wrapper)
                    .setPositiveButton(R.string.import_unlock_action, null)
                    .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
            )
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.setOnDismissListener { if (cont.isActive) cont.resume(typed) }
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = field.text?.toString().orEmpty()
                if (text.isEmpty()) return@setOnClickListener
                typed = text
                dialog.dismiss()
            }
        }
}
