package com.symmetricalpalmtree.soil.cloud

import android.content.Context
import android.content.Intent
import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.CloudStoreLease
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.ExtensionBinder
import com.symmetricalpalmtree.soil.ext.ICloudStorage
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **The connect showing**: the one held bind on the cloud point. The sign-in is a screen the
 * extension owns (Soil has no INTERNET and never sees a token), but the screen must persist what
 * it wins into the store Soil lends, so the store has to outlive one call. The bracket: lease the
 * store, hold the bind, `beginConnect(store)`, the caller launches the screen for a result with
 * nothing riding the Intent, then [finish]: `endConnect` best effort, unbind and revoke in one
 * `finally`. The screen answers `RESULT_OK` only once the token is in the store, so Soil's next
 * `status` is the truth and the caller re-reads it either way.
 */
class CloudConnectClient(context: Context, val ref: Extension) {

    private val app = context.applicationContext
    private var held: ExtensionBinder? = null
    private var store: CloudStoreLease? = null

    val isOpen: Boolean get() = held != null

    /** Lease, hold, `beginConnect`, and build the screen Intent; or null, the reason logged. */
    suspend fun open(): Intent? {
        if (held != null) { Slog.d(TAG) { "open: already open" }; return null }
        val t0 = System.currentTimeMillis()
        val lease = CloudStoreLease.lease(app, ref.packageName, TAG) ?: return null
        val bound = try {
            withContext(Dispatchers.IO) { ExtensionBinder.bind(app, CloudContract.ACTION_CLOUD_STORAGE, ref.component) }
        } catch (e: CancellationException) {
            lease.revoke(); throw e
        } catch (e: Exception) {
            lease.revoke()
            Slog.d(TAG) { "open failed: bind ${e.javaClass.simpleName}" }
            return null
        }
        held = bound
        store = lease
        try {
            withContext(Dispatchers.IO) { bound.call(CALL_TIMEOUT_MS) { ICloudStorage.Stub.asInterface(bound.binder).beginConnect(lease) } }
        } catch (e: CancellationException) {
            finish(); throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "open failed: beginConnect ${e.javaClass.simpleName}" }
            finish()
            return null
        }
        Slog.d(TAG) { "open: ready in ${System.currentTimeMillis() - t0} ms" }
        return Intent(CloudContract.ACTION_CLOUD_SCREEN).setPackage(ref.packageName)
    }

    /** `endConnect` (best effort), then unbind and revoke. Idempotent; the backstop for a caller destroyed mid-showing. */
    suspend fun finish() {
        val bound = held ?: return
        held = null
        val lease = store
        store = null
        try {
            if (bound.binder.isBinderAlive) withContext(Dispatchers.IO) { bound.call(CALL_TIMEOUT_MS) { ICloudStorage.Stub.asInterface(bound.binder).endConnect() } }
            Slog.d(TAG) { "finish: endConnect ok" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "finish: endConnect failed ${e.javaClass.simpleName}" }
        } finally {
            bound.close()
            lease?.revoke()
        }
    }

    companion object {
        const val TAG = "CloudConnectClient"

        /** Two store-less calls that park and forget a reference: nothing here for a larger number to cover. */
        const val CALL_TIMEOUT_MS = 2_000L
    }
}
