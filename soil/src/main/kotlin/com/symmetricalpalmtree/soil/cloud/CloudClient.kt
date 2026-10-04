package com.symmetricalpalmtree.soil.cloud

import android.content.Context
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.ext.CloudStoreLease
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.ExtensionBinder
import com.symmetricalpalmtree.soil.ext.ExtensionCallFailed
import com.symmetricalpalmtree.soil.ext.ICloudStorage
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No account is connected. Soil's answer is to offer Connect. Nothing was changed. */
class CloudNotConnected(cause: Throwable) : ExtensionCallFailed(CloudContract.NOT_CONNECTED, cause)

/** The provider could not reach its service: offline, a timeout of its own, a 5xx. Nothing changed; try again. */
class CloudNetworkFailed(cause: Throwable) : ExtensionCallFailed(CloudContract.NETWORK, cause)

/**
 * **Soil's side of the cloud point**: one bind, one call, one unbind per operation, the store
 * opened on IO before the bind (a cold open is seconds) and lent for the call alone. Each call
 * runs under its own budget from [CloudTimeouts]; a Binder call cannot be cancelled, so a budget
 * that runs out leaves the provider still working while Soil has already spoken, and nothing
 * here is retried on its own. Every operation is replace-by-name or idempotent, so "try again"
 * is safe to offer.
 *
 * What crosses: folder names, an opaque entry id, a MIME type, descriptors. No secret, no device
 * path, no URL, in either direction. The account label is the person's own and is never logged.
 *
 * The two typed refusals are lifted out of the stub's `IllegalStateException` by a verbatim
 * message match: [CloudContract.NOT_CONNECTED] → [CloudNotConnected], [CloudContract.NETWORK] →
 * [CloudNetworkFailed]. Anything else is the plain [ExtensionCallFailed], "the provider did not
 * answer". Arguments are checked here, before the bind, by [CloudArgs]: a refusal must never
 * start a process. Descriptors given to [upload] and [download] are owned from that moment and
 * closed here on every path.
 */
object CloudClient {

    const val TAG = "CloudClient"

    /** What the store says about the account. Never touches the network, so cheap enough for a resume. */
    suspend fun status(context: Context, ref: Extension): CloudStatus {
        val t0 = System.currentTimeMillis()
        val status = call(context, ref, "status", CloudTimeouts.STATUS_MS) { iface, store ->
            iface.status(store) ?: throw ExtensionCallFailed("status returned nothing")
        }
        Slog.d(TAG) { "status: configured=${status.configured} connected=${status.connected} in ${System.currentTimeMillis() - t0} ms" }
        return status
    }

    /** Forget the account: the provider revokes its token (best effort) and clears the store. Idempotent. */
    suspend fun disconnect(context: Context, ref: Extension) {
        call(context, ref, "disconnect", CloudTimeouts.DISCONNECT_MS) { iface, store -> iface.disconnect(store) }
        Slog.d(TAG) { "disconnect: done" }
    }

    /** What is directly under [path]: folders first, then files, each by name. A missing folder lists empty. */
    suspend fun list(context: Context, ref: Extension, path: Array<String>): List<CloudEntry> {
        CloudArgs.requirePath(path)
        val t0 = System.currentTimeMillis()
        val entries = call(context, ref, "list", CloudTimeouts.LIST_MS) { iface, store -> CloudArgs.checkList(iface.list(store, path)) }
        Slog.d(TAG) { "list: depth=${path.size} → ${entries.size} entries in ${System.currentTimeMillis() - t0} ms" }
        return entries
    }

    /** Find or create each segment of [path] and answer the last; an empty path is the root. */
    suspend fun ensureFolder(context: Context, ref: Extension, path: Array<String>): CloudEntry {
        CloudArgs.requirePath(path)
        val t0 = System.currentTimeMillis()
        val entry = call(context, ref, "ensureFolder", CloudTimeouts.ENSURE_FOLDER_MS) { iface, store -> CloudArgs.checkFolder(iface.ensureFolder(store, path)) }
        Slog.d(TAG) { "ensureFolder: depth=${path.size} in ${System.currentTimeMillis() - t0} ms" }
        return entry
    }

    /**
     * Write [source] as the file [name] under [path], folders made on the way, replacing a file
     * of that name in place. [expectedBytes] is what the caller wrote; the provider streams
     * exactly that many. The returned size is corroboration, judged by the caller, never a
     * reason to delete. [source] is closed here whatever happens.
     */
    suspend fun upload(context: Context, ref: Extension, path: Array<String>, name: String, mime: String, source: ParcelFileDescriptor, expectedBytes: Long): CloudEntry {
        try {
            CloudArgs.requirePath(path)
            CloudArgs.requireName(name)
            CloudArgs.requireMime(mime)
            CloudArgs.requireExpectedBytes(expectedBytes)
            val t0 = System.currentTimeMillis()
            val entry = call(context, ref, "upload", CloudTimeouts.uploadBudgetMs(expectedBytes)) { iface, store ->
                CloudArgs.checkUploaded(iface.upload(store, path, name, mime, source, expectedBytes))
            }
            Slog.d(TAG) { "upload: $expectedBytes B → ${entry.sizeBytes} B reported, agrees=${entry.sizeBytes == expectedBytes} in ${System.currentTimeMillis() - t0} ms" }
            return entry
        } finally {
            runCatching { source.close() }
        }
    }

    /** Stream the file [entryId] into [destination] (truncated first) and answer the bytes written. [destination] is closed here. */
    suspend fun download(context: Context, ref: Extension, entryId: String, destination: ParcelFileDescriptor, budgetMs: Long = CloudTimeouts.DOWNLOAD_MS): Long {
        try {
            CloudArgs.requireEntryId(entryId)
            val t0 = System.currentTimeMillis()
            val bytes = call(context, ref, "download", budgetMs) { iface, store -> CloudArgs.checkDownloaded(iface.download(store, entryId, destination)) }
            Slog.d(TAG) { "download: $bytes B in ${System.currentTimeMillis() - t0} ms" }
            return bytes
        } finally {
            runCatching { destination.close() }
        }
    }

    /** Delete the file or folder [entryId]. Idempotent on one already gone. */
    suspend fun delete(context: Context, ref: Extension, entryId: String) {
        CloudArgs.requireEntryId(entryId)
        call(context, ref, "delete", CloudTimeouts.DELETE_MS) { iface, store -> iface.delete(store, entryId) }
        Slog.d(TAG) { "delete: done" }
    }

    private suspend fun <T> call(context: Context, ref: Extension, op: String, timeoutMs: Long, block: (ICloudStorage, CloudStoreLease) -> T): T {
        val app = context.applicationContext
        val store = CloudStoreLease.lease(app, ref.packageName, TAG) ?: throw ExtensionCallFailed("store unavailable for $op")
        try {
            return withContext(Dispatchers.IO) {
                ExtensionBinder.once(app, CloudContract.ACTION_CLOUD_STORAGE, ref.component, timeoutMs) { binder ->
                    mapRefusals { block(ICloudStorage.Stub.asInterface(binder), store) }
                }
            }
        } finally {
            store.revoke()
        }
    }

    private inline fun <T> mapRefusals(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            when (e.message) {
                CloudContract.NOT_CONNECTED -> throw CloudNotConnected(e)
                CloudContract.NETWORK -> throw CloudNetworkFailed(e)
                else -> throw e
            }
        }
}
