package com.symmetricalpalmtree.soil.cloud

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.export.ExportDestination
import com.symmetricalpalmtree.soil.export.ExportVerification
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.importing.CloudImportRules
import com.symmetricalpalmtree.soil.importing.ImportDialogs
import com.symmetricalpalmtree.soil.importing.ImportOverlay
import com.symmetricalpalmtree.soil.importing.ImportSource
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The cloud as a second place for files**, for any screen that offers the device's file picker
 * (cleanup, 2026-10-10): the template library's import and export, and the file-pick screen the
 * Sprout apps start. One owner, one shape, the Import button's: with a provider installed the
 * tap first asks *this device, or the provider*; the cloud answer is judged by the Export
 * screen's rule (connected opens the browser, no account offers Connect, a build without
 * credentials says so); the browser answers a file or a folder; and the bytes go down into a
 * file of the caller's or up from one, corroborated as the import and the export corroborate
 * theirs. Registered from the owner's `onCreate`: the connect door's launcher may not be
 * registered later. Closed from its `onDestroy`.
 */
class CloudFilePick(private val activity: AppCompatActivity) {

    /** Why a transfer did not happen. [NOT_CONNECTED] is the one that offers Connect. */
    enum class Failure { GONE, NOT_CONNECTED, NETWORK, UNANSWERED, WRITE, SHORT, UNCONFIRMED }

    private val connect = CloudConnectEntry(activity) { wasConnected -> onConnectResult(wasConnected) }
    private var ref: Extension? = null
    private var status: CloudStatus? = null
    private var browser: CloudBrowserDialog? = null
    /** The browser to open again once a sign-in opened from here comes back connected. */
    private var afterConnect: (() -> Unit)? = null

    val isInstalled: Boolean get() = ref != null

    /** Looked for again at every tap: an extension can come and go under a standing screen. */
    suspend fun discover(): Boolean {
        val found = connect.discover()
        ref = found
        status = if (found == null) null else try {
            CloudClient.status(activity, found)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}" }
            null
        }
        return found != null
    }

    fun providerName(): String = ExportDestination.providerName(status, ref?.label.orEmpty())

    /**
     * The question, asked only when there is one: this device, or the provider. Without a
     * provider the answer is the device at once; Cancel is null.
     */
    suspend fun askSource(@StringRes titleRes: Int): ImportSource.Source? {
        if (!ImportSource.asksSource(isInstalled)) return ImportSource.Source.LOCAL
        val answer = ImportDialogs.pickFromList(activity, titleRes, listOf(activity.getString(R.string.import_source_device), providerName())) ?: return null
        return ImportSource.sourceAt(answer, isInstalled)
    }

    /** The browser over the provider's root in file mode. Exactly one of the two callbacks runs. */
    fun pickFile(onPicked: (Extension, CloudEntry) -> Unit, onGaveUp: () -> Unit) {
        gate(onGaveUp) { provider ->
            openBrowser(provider, CloudBrowserDialog.Mode.PICK_FILE, basePath = emptyList(), startPath = emptyList(), onGaveUp) { pick ->
                when (pick) {
                    is CloudBrowserDialog.Pick.File -> onPicked(provider, pick.entry)
                    is CloudBrowserDialog.Pick.Folder -> onGaveUp()
                }
            }
        }
    }

    /** The browser over [basePath] in folder mode, opened on [startPath]; *Save here* answers the folder with its listing. */
    fun pickFolder(basePath: List<String>, startPath: List<String>, onPicked: (Extension, List<String>, List<CloudEntry>) -> Unit, onGaveUp: () -> Unit) {
        gate(onGaveUp) { provider ->
            openBrowser(provider, CloudBrowserDialog.Mode.PICK_FOLDER, basePath, startPath, onGaveUp) { pick ->
                when (pick) {
                    is CloudBrowserDialog.Pick.Folder -> onPicked(provider, pick.path, pick.listing)
                    is CloudBrowserDialog.Pick.File -> onGaveUp()
                }
            }
        }
    }

    /** The Export screen's rule: connected goes on; a build without credentials says so; anything else offers Connect, and the sign-in's return goes on. */
    private fun gate(onGaveUp: () -> Unit, proceed: (Extension) -> Unit) {
        val provider = ref
        if (provider == null) { explain(Failure.GONE, R.string.cloud_pick_failed_title, put = false); onGaveUp(); return }
        when (ExportDestination.onCloudTap(status)) {
            ExportDestination.Tap.SELECT -> proceed(provider)
            ExportDestination.Tap.NOT_CONFIGURED -> { Dialogs.problem(activity, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body); onGaveUp() }
            ExportDestination.Tap.OFFER_CONNECT -> offerConnect(onGaveUp) { ref?.let(proceed) ?: onGaveUp() }
        }
    }

    private fun offerConnect(onGaveUp: () -> Unit, then: () -> Unit) {
        if (!connect.isAvailable || activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
        val name = providerName()
        var connecting = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity).setTitle(activity.getString(R.string.cloud_connect_offer_title, name)).setMessage(activity.getString(R.string.cloud_pick_connect_offer_body, name))
                .setPositiveButton(R.string.cloud_connect) { _, _ -> connecting = true; afterConnect = then; connect.open() }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        )
        dialog.setOnDismissListener { if (!connecting) onGaveUp() }
        dialog.show()
    }

    /** The sign-in came back: connected, the pending browser opens; otherwise the beat ends quietly. */
    private fun onConnectResult(wasConnected: Boolean) {
        val pending = afterConnect ?: return
        afterConnect = null
        if (!wasConnected) return
        activity.lifecycleScope.launch {
            discover()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (status?.connected == true) pending()
        }
    }

    private fun openBrowser(provider: Extension, mode: CloudBrowserDialog.Mode, basePath: List<String>, startPath: List<String>, onGaveUp: () -> Unit, onPicked: (CloudBrowserDialog.Pick) -> Unit) {
        browser?.dismiss()
        val dialog = CloudBrowserDialog(
            activity = activity,
            source = CloudSource(activity, provider, providerName()),
            mode = mode,
            basePath = basePath,
            startPath = startPath,
            onPicked = { pick -> browser = null; onPicked(pick) },
            onNotConnected = {
                browser = null
                activity.lifecycleScope.launch {
                    discover()
                    if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return@launch }
                    offerConnect(onGaveUp) { ref?.let { openBrowser(it, mode, basePath, startPath, onGaveUp, onPicked) } ?: onGaveUp() }
                }
            },
            onCancelled = { browser = null; onGaveUp(); Slog.d(TAG) { "cloud browser cancelled" } },
        )
        browser = dialog
        dialog.show()
    }

    // ── The bytes ──────

    /**
     * [entry] streamed into [into], under the wait overlay. What the provider reports, what
     * landed and what the listing said must agree. Null is success.
     */
    suspend fun download(provider: Extension, entry: CloudEntry, into: File): Failure? {
        ImportOverlay.show(activity, R.string.cloud_browser_loading)
        ImportOverlay.stage(activity, activity.getString(R.string.import_stage_downloading, providerName()))
        try {
            val destination = withContext(Dispatchers.IO) {
                runCatching {
                    into.parentFile?.mkdirs()
                    ParcelFileDescriptor.open(into, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                }.getOrNull()
            } ?: return Failure.WRITE
            val reported = try {
                CloudClient.download(activity, provider, entry.id, destination, CloudTimeouts.downloadBudgetMs(entry.sizeBytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNotConnected) {
                return Failure.NOT_CONNECTED
            } catch (e: CloudNetworkFailed) {
                return Failure.NETWORK
            } catch (e: Exception) {
                Slog.d(TAG) { "download failed: ${e.javaClass.simpleName}" }
                return Failure.UNANSWERED
            }
            val landed = withContext(Dispatchers.IO) { into.length() }
            return when (CloudImportRules.downloadVerdict(reported, landed, entry.sizeBytes)) {
                CloudImportRules.Verdict.SHORT -> { Log.w(TAG, "download landed $landed of $reported reported (${entry.sizeBytes} listed) bytes"); Failure.SHORT }
                CloudImportRules.Verdict.DISAGREE -> { Log.w(TAG, "download landed $landed; the listing said ${entry.sizeBytes}"); null }
                CloudImportRules.Verdict.OK -> null
            }
        } finally {
            ImportOverlay.hide(activity)
        }
    }

    /** [file] put into [path] as [name], replacing by name, under the wait overlay. The size the provider reports must match. Null is success. */
    suspend fun upload(provider: Extension, path: List<String>, name: String, mime: String, file: File): Failure? {
        ImportOverlay.show(activity, R.string.cloud_browser_loading)
        ImportOverlay.stage(activity, activity.getString(R.string.export_uploading, providerName()))
        try {
            val bytes = withContext(Dispatchers.IO) { file.length() }
            val pfd = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() } ?: return Failure.WRITE
            val entry = try {
                CloudClient.upload(activity, provider, path.toTypedArray(), name, mime, pfd, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNotConnected) {
                return Failure.NOT_CONNECTED
            } catch (e: CloudNetworkFailed) {
                return Failure.NETWORK
            } catch (e: Exception) {
                Slog.d(TAG) { "upload failed: ${e.javaClass.simpleName}" }
                return Failure.UNANSWERED
            }
            if (ExportVerification.cloudVerdict(entry.sizeBytes, bytes) != ExportVerification.Verdict.OK) {
                Log.w(TAG, "the provider reports ${entry.sizeBytes} for $bytes uploaded bytes")
                return Failure.UNCONFIRMED
            }
            return null
        } finally {
            ImportOverlay.hide(activity)
        }
    }

    /** The honest sentence for a [failure]: what did not happen, and Connect where that is the one thing that helps. [put] words it for an upload. */
    fun explain(failure: Failure, @StringRes titleRes: Int, put: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val name = providerName()
        val body = when (failure) {
            Failure.GONE -> activity.getString(if (put) R.string.cloud_put_gone_body else R.string.cloud_pick_gone_body)
            Failure.NETWORK -> activity.getString(if (put) R.string.cloud_put_network_body else R.string.cloud_pick_network_body, name)
            Failure.UNANSWERED -> activity.getString(if (put) R.string.cloud_put_unanswered_body else R.string.cloud_pick_unanswered_body, name)
            Failure.WRITE -> activity.getString(if (put) R.string.cloud_put_read_body else R.string.cloud_pick_write_body)
            Failure.SHORT -> activity.getString(R.string.cloud_pick_short_body, name)
            Failure.UNCONFIRMED -> activity.getString(R.string.cloud_put_unconfirmed_body, name)
            Failure.NOT_CONNECTED -> {
                Dialogs.style(
                    AlertDialog.Builder(activity).setTitle(titleRes).setMessage(activity.getString(if (put) R.string.cloud_put_not_connected_body else R.string.cloud_pick_not_connected_body, name))
                        .setPositiveButton(R.string.cloud_connect) { _, _ -> afterConnect = null; connect.open() }
                        .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
                ).show()
                return
            }
        }
        Dialogs.problem(activity, titleRes, body)
    }

    /** From the owner's `onDestroy`: the browser and a sign-in's bind must not outlive the screen. */
    fun close() {
        browser?.dismiss()
        browser = null
        afterConnect = null
        connect.close()
    }

    private companion object { const val TAG = "CloudFilePick" }
}
