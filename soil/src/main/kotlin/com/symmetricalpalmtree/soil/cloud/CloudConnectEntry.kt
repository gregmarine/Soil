package com.symmetricalpalmtree.soil.cloud

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.importing.ImportOverlay
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The connect door**, owned by a screen: whether a provider is installed (asked again each time
 * the door is about to be offered), the busy latch at the tap, the wait overlay while the store
 * opens and the bind is held, and the bind's life, finished from the result and from [close] as
 * the backstop. It learns nothing about the account from the result: [onChanged] runs on every
 * result, and on a sign-in that could not be opened, and the caller re-reads the status.
 * Registered from the caller's `onCreate`: a launcher may not be registered after STARTED.
 */
class CloudConnectEntry(private val activity: AppCompatActivity, private val onChanged: (wasConnected: Boolean) -> Unit = {}) {

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> onResult(result) }

    private var client: CloudConnectClient? = null
    private var opening = false

    /** The provider the last [discover] found. */
    var ref: Extension? = null
        private set

    suspend fun discover(): Extension? {
        val found = withContext(Dispatchers.IO) { runCatching { CloudProviders.installed(activity) }.getOrNull() }
        if (activity.isFinishing || activity.isDestroyed) return found
        ref = found
        return found
    }

    val isAvailable: Boolean get() = ref != null

    /** Raise the overlay, then behind it lease the store, hold the bind, `beginConnect`, and launch the sign-in. */
    fun open() {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        ImportOverlay.show(activity, R.string.cloud_opening)
        activity.lifecycleScope.launch {
            val fresh = CloudConnectClient(activity, provider)
            client = fresh
            val intent = fresh.open()
            if (activity.isFinishing || activity.isDestroyed) { client = null; opening = false; fresh.finish(); return@launch }
            if (intent == null) {
                client = null
                opening = false
                fresh.finish()
                ImportOverlay.hide(activity)
                Dialogs.problem(activity, R.string.cloud_connect_failed_title, R.string.cloud_connect_failed_body)
                discover()
                // A result always arrives, so a caller holding a latch across the sign-in is let go.
                if (!activity.isFinishing && !activity.isDestroyed) onChanged(false)
                return@launch
            }
            ImportOverlay.hide(activity)
            try {
                launcher.launch(intent)
            } catch (e: Exception) {
                Slog.d(TAG) { "launch failed: ${e.javaClass.simpleName}" }
                client = null
                opening = false
                fresh.finish()
                Dialogs.problem(activity, R.string.cloud_connect_failed_title, R.string.cloud_connect_failed_body)
                if (!activity.isFinishing && !activity.isDestroyed) onChanged(false)
            }
        }
    }

    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        val connected = result.resultCode == Activity.RESULT_OK
        Slog.d(TAG) { "connect screen returned: resultCode=${result.resultCode}" }
        MainScope().launch {
            try {
                open?.finish()
            } finally {
                opening = false
            }
            if (!activity.isFinishing && !activity.isDestroyed) onChanged(connected)
        }
    }

    /** The bind must not outlive the screen that opened it. From the caller's `onDestroy`. */
    fun close() {
        opening = false
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }

    private companion object { const val TAG = "CloudConnectEntry" }
}
