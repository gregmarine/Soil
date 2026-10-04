package com.symmetricalpalmtree.soil.restore

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.backup.BackupConfig
import com.symmetricalpalmtree.soil.backup.BackupStore
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.crypto.SecurePrefs
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **The backup destination is this device's, and a restore never rewrites it.** The whole config
 * rides inside the index being replaced, so a naive restore would install the source device's
 * folder, its cloud folder and stamps for files this device never wrote. This device's three
 * destination fields are parked before the swap and merged back over the restored row on the
 * first open after it; both stamp maps and every last-run figure are cleared.
 */
object RestoreDestination {

    @Serializable
    data class Parked(val treeUri: String? = null, val cloudEnabled: Boolean = false, val cloudDeviceFolder: String? = null)

    fun merge(restored: BackupConfig, parked: Parked?): BackupConfig = restored.copy(
        treeUri = parked?.treeUri,
        cloudEnabled = parked?.cloudEnabled ?: false,
        cloudDeviceFolder = parked?.cloudDeviceFolder,
        stamps = emptyMap(), cloudStamps = emptyMap(),
        lastRunAt = null, lastCopied = null, lastSkipped = null,
        cloudLastRunAt = null, cloudLastCopied = null, cloudLastSkipped = null,
    )

    fun parkedFrom(config: BackupConfig): Parked = Parked(config.treeUri, config.cloudEnabled, config.cloudDeviceFolder)

    private const val KEY = "restore_pending_destination"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun park(context: Context, parked: Parked): Boolean = try {
        prefs(context).edit().putString(KEY, json.encodeToString(Parked.serializer(), parked)).commit()
    } catch (e: Exception) {
        Log.w(TAG, "park failed", e)
        false
    }

    fun parked(context: Context): Parked? = try {
        prefs(context).getString(KEY, null)?.let { json.decodeFromString(Parked.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    fun clearPark(context: Context) { runCatching { prefs(context).edit().remove(KEY).commit() } }

    fun hasPark(context: Context): Boolean = prefs(context).contains(KEY)

    /** The first-open step: merge a standing park over the restored row, write first, clear second. Only with the index open. */
    fun applyParked(context: Context, store: BackupStore = BackupStore()) {
        val app = context.applicationContext
        val parked = try { if (!hasPark(app)) return; parked(app) } catch (e: Exception) { return }
        try {
            if (!store.write(merge(store.read(), parked))) { Log.w(TAG, "destination re-apply did not write; park kept"); return }
            clearPark(app)
            Slog.d(TAG) { "destination re-applied after restore (tree=${parked?.treeUri != null}, cloud=${parked?.cloudEnabled == true})" }
        } catch (e: Exception) {
            Log.w(TAG, "destination re-apply failed; park kept for the next launch", e)
        }
    }

    private fun prefs(context: Context) = SecurePrefs.get(context, PassphraseStore.PREFS_FILE)

    private const val TAG = "RestoreDestination"
}
