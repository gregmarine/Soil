package com.symmetricalpalmtree.soil.paper.recognition

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.paper.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The model-consent flow, in Soil's words, shared by every app: make the recogniser ready,
 * asking the person first when that needs a download, then run [onReady], or [onGaveUp] when the
 * flow ended without it. Exactly one of the two runs, on Main.
 *
 * READY → [onReady] · NEEDS_DOWNLOAD → the one-time "Recognition model needed" dialog (offline
 * checked first: the downloader hangs rather than fails with no network) → `prepare` and a
 * progress dialog polling the status every [POLL_MS] until READY · DOWNLOADING → the progress
 * dialog · UNAVAILABLE, no recogniser → a problem dialog.
 *
 * The progress indicator is an elapsed-seconds counter, not a spinner: a number that changes
 * every two seconds reads as "still working" for the price of one e-ink refresh. Cancel hides
 * the dialog only: the download keeps running in the extension.
 */
object RecognizerReadiness {

    private const val TAG = "RecognizerReadiness"
    const val POLL_MS = 2_000L
    const val DOWNLOAD_CAP_S = 300
    const val OFFLINE_GIVE_UP_MS = 30_000L
    const val MAX_POLL_FAILURES = 5

    fun ensureReady(activity: AppCompatActivity, port: RecognizerPort, onReady: suspend () -> Unit, onGaveUp: () -> Unit) {
        activity.lifecycleScope.launch {
            val status = try {
                port.status()
            } catch (e: RecognizerCallException) {
                Slog.d(TAG) { "status failed: ${e.message}" }
                Dialogs.problem(activity, R.string.recognize_problem_title, if (e.noRecognizer) R.string.recognize_no_recognizer else R.string.recognize_failed)
                onGaveUp()
                return@launch
            }
            when (status) {
                RecognizerPort.STATUS_READY -> onReady()
                RecognizerPort.STATUS_UNAVAILABLE -> {
                    Dialogs.problem(activity, R.string.recognize_problem_title, R.string.recognize_unavailable)
                    onGaveUp()
                }
                RecognizerPort.STATUS_DOWNLOADING -> awaitDownload(activity, port, prepare = false, onReady, onGaveUp)
                else -> promptDownload(activity, port, onReady, onGaveUp)
            }
        }
    }

    private fun promptDownload(activity: AppCompatActivity, port: RecognizerPort, onReady: suspend () -> Unit, onGaveUp: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.recognize_model_needed_title)
            .setOnCancelListener { onGaveUp() }
        if (Connectivity.isOnline(activity)) {
            builder.setMessage(R.string.recognize_model_needed_body)
                .setPositiveButton(R.string.recognize_download) { _, _ -> awaitDownload(activity, port, prepare = true, onReady, onGaveUp) }
                .setNegativeButton(R.string.cancel) { _, _ -> onGaveUp() }
        } else {
            builder.setMessage(R.string.recognize_model_needed_offline_body)
                .setPositiveButton(R.string.ok) { _, _ -> onGaveUp() }
        }
        Dialogs.style(builder.create()).show()
    }

    private fun awaitDownload(activity: AppCompatActivity, port: RecognizerPort, prepare: Boolean, onReady: suspend () -> Unit, onGaveUp: () -> Unit) {
        activity.lifecycleScope.launch {
            var cancelled = false
            var settled = false
            val progress = AlertDialog.Builder(activity)
                .setTitle(R.string.recognize_downloading_title)
                .setMessage(activity.getString(R.string.recognize_downloading_body, 0))
                .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
                .setOnCancelListener { cancelled = true }
                .create()
            Dialogs.style(progress)

            suspend fun succeed() { settled = true; progress.dismiss(); onReady() }
            fun fail() {
                settled = true
                if (progress.isShowing) progress.dismiss()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.recognize_download_failed_title, R.string.recognize_download_failed_body)
                }
                onGaveUp()
            }

            try {
                if (prepare) {
                    try {
                        port.prepare()
                    } catch (e: RecognizerCallException) {
                        Slog.d(TAG) { "prepare failed: ${e.message}" }
                        fail(); return@launch
                    }
                }
                if (activity.isFinishing || activity.isDestroyed) { settled = true; onGaveUp(); return@launch }
                progress.show()

                val t0 = System.currentTimeMillis()
                var offlineSinceMs = -1L
                var pollFailures = 0
                while (!cancelled) {
                    delay(POLL_MS)
                    if (cancelled || activity.isFinishing || activity.isDestroyed) break
                    val now = System.currentTimeMillis()
                    val elapsedS = ((now - t0) / 1000L).toInt()
                    val status = try {
                        port.status().also { pollFailures = 0 }
                    } catch (e: RecognizerCallException) {
                        pollFailures++
                        if (pollFailures >= MAX_POLL_FAILURES) { fail(); return@launch }
                        RecognizerPort.STATUS_DOWNLOADING
                    }
                    if (status == RecognizerPort.STATUS_READY) {
                        Slog.d(TAG) { "model ready after $elapsedS s" }
                        succeed(); return@launch
                    }
                    if (!Connectivity.isOnline(activity)) {
                        if (offlineSinceMs < 0) offlineSinceMs = now
                        if (now - offlineSinceMs >= OFFLINE_GIVE_UP_MS) { fail(); return@launch }
                        progress.setMessage(activity.getString(R.string.recognize_downloading_offline_body, elapsedS))
                        continue
                    }
                    offlineSinceMs = -1L
                    when (status) {
                        RecognizerPort.STATUS_DOWNLOADING -> {
                            progress.setMessage(activity.getString(R.string.recognize_downloading_body, elapsedS))
                            if (elapsedS >= DOWNLOAD_CAP_S) { fail(); return@launch }
                        }
                        else -> { fail(); return@launch }
                    }
                }
                Slog.d(TAG) { "download dialog cancelled; the download continues" }
            } finally {
                if (progress.isShowing) progress.dismiss()
                if (!settled) onGaveUp()
            }
        }
    }
}
