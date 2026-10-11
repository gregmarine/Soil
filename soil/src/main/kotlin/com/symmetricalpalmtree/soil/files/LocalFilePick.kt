package com.symmetricalpalmtree.soil.files

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.cloud.CloudBrowserDialog
import com.symmetricalpalmtree.soil.cloud.LocalSource
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.settings.SettingsPrefs

/**
 * **A file or a folder on this device**, through Soil's own browser (Greg, 2026-10-10). With
 * All files access on, the browser opens over the shared storage's root and answers a [File].
 * Without it the first pick offers the system Settings screen once; *Use Android's picker*
 * answers [Answer.UseSystemPicker] and is remembered, so the caller opens the Android picker as
 * before, now and at every later pick, until the Settings row is tapped. Exactly one answer per
 * pick.
 */
class LocalFilePick(private val activity: AppCompatActivity) {

    sealed class Answer {
        class File(val file: java.io.File) : Answer()
        class Folder(val dir: java.io.File) : Answer()
        /** The access is off and the person chose, or has chosen, the Android picker. */
        object UseSystemPicker : Answer()
        object GaveUp : Answer()
    }

    private val prefs = SettingsPrefs(activity)
    private var browser: CloudBrowserDialog? = null

    val hasAccess: Boolean get() = LocalStorage.hasAccess()

    /** Whether a pick goes through Soil's browser: the access is on. The Android picker otherwise, offered once. */
    fun browses(): Boolean = hasAccess

    fun pickFile(mimes: Array<String>?, onAnswer: (Answer) -> Unit) {
        if (!gate(onAnswer)) return
        open(CloudBrowserDialog.Mode.PICK_FILE, start = emptyList(), mimes = mimes, onAnswer = onAnswer)
    }

    /** The browser in folder mode, opened on [start] under the root (the root itself for a folder that is gone). */
    fun pickFolder(start: List<String>, onAnswer: (Answer) -> Unit) {
        if (!gate(onAnswer)) return
        val root = LocalStorage.root()
        val opening = if (runCatching { LocalSource(root, "").resolve(start).isDirectory }.getOrDefault(false)) start else emptyList()
        open(CloudBrowserDialog.Mode.PICK_FOLDER, start = opening, mimes = null, onAnswer = onAnswer)
    }

    /** True when the browser may open. False when the answer was given here: the Android picker, or nothing. */
    private fun gate(onAnswer: (Answer) -> Unit): Boolean {
        if (LocalStorage.settleAfterGrant(activity)) { onAnswer(Answer.GaveUp); return false }
        if (hasAccess) return true
        if (prefs.localPickerDeclined) { onAnswer(Answer.UseSystemPicker); return false }
        if (activity.isFinishing || activity.isDestroyed) { onAnswer(Answer.GaveUp); return false }
        var answered = false
        fun once(a: Answer) { if (!answered) { answered = true; onAnswer(a) } }
        Dialogs.style(
            AlertDialog.Builder(activity).setTitle(R.string.files_offer_title).setMessage(R.string.files_offer_body)
                .setPositiveButton(R.string.files_offer_settings) { _, _ ->
                    if (!LocalStorage.openSettings(activity)) { prefs.localPickerDeclined = true; Dialogs.problem(activity, R.string.files_settings_failed_title, R.string.files_settings_failed_body) }
                    once(Answer.GaveUp)
                }
                .setNeutralButton(R.string.files_offer_android) { _, _ -> prefs.localPickerDeclined = true; once(Answer.UseSystemPicker) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        ).also { it.setOnDismissListener { once(Answer.GaveUp) } }.show()
        return false
    }

    private fun open(mode: CloudBrowserDialog.Mode, start: List<String>, mimes: Array<String>?, onAnswer: (Answer) -> Unit) {
        browser?.dismiss()
        val root = LocalStorage.root()
        val source = LocalSource(root, LocalStorage.label(activity), mimes)
        val dialog = CloudBrowserDialog(
            activity = activity,
            source = source,
            mode = mode,
            basePath = emptyList(),
            startPath = start,
            onPicked = { pick ->
                browser = null
                when (pick) {
                    is CloudBrowserDialog.Pick.File -> source.fileFor(pick.entry.id)?.let { onAnswer(Answer.File(it)) } ?: onAnswer(Answer.GaveUp)
                    is CloudBrowserDialog.Pick.Folder -> onAnswer(Answer.Folder(runCatching { source.resolve(pick.path) }.getOrDefault(root)))
                }
            },
            onCancelled = { browser = null; onAnswer(Answer.GaveUp); Slog.d(TAG) { "local browser cancelled" } },
        )
        browser = dialog
        dialog.show()
    }

    fun close() { browser?.dismiss(); browser = null }

    private companion object { const val TAG = "LocalFilePick" }
}
