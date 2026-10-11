package com.symmetricalpalmtree.soil.backup

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.soil.cloud.CloudArgs
import com.symmetricalpalmtree.soil.cloud.CloudClient
import com.symmetricalpalmtree.soil.cloud.CloudNetworkFailed
import com.symmetricalpalmtree.soil.cloud.CloudNotConnected
import com.symmetricalpalmtree.soil.cloud.CloudProviders
import com.symmetricalpalmtree.soil.crypto.KeyMaterial
import com.symmetricalpalmtree.soil.crypto.OpenFiles
import com.symmetricalpalmtree.soil.data.SoilDb
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.export.ExportVerification
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.ExtensionCallFailed
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The cloud leg**: the same run, a second destination, `Backups/<device folder>/` under the
 * provider's root. Every uploaded file is self-contained ([SelfContainedSnapshot]); one listing
 * at the start, kept current, serves the stale-sidecar check, the arc's one remote delete; stamps
 * are the cloud's own, written per success; the leg stops where it stands on a not-connected, a
 * network failure or a no-answer, or on the person's Cancel asked before every unit, keeping
 * what it earned. Nothing here logs a name or an account.
 */
internal object CloudBackupLeg {

    private const val TAG = "CloudBackupLeg"
    private const val MIME = "application/octet-stream"

    private sealed class Sent {
        object Ok : Sent()
        object Refused : Sent()
        class Stopped(val problem: BackupEngine.Problem) : Sent()
    }

    suspend fun run(app: Context, ref: Extension, state: RunState, work: BackupPredicates.WorkList, stores: List<BackupEngine.StoreFile>, aliveIds: Set<String>, compacted: MutableSet<String>, stop: () -> Boolean, tick: () -> Unit): BackupEngine.Result = try {
        runLeg(app, ref, state, work, stores, aliveIds, compacted, stop, tick)
    } finally {
        SelfContainedSnapshot.clean(app)
    }

    private suspend fun runLeg(app: Context, ref: Extension, state: RunState, work: BackupPredicates.WorkList, stores: List<BackupEngine.StoreFile>, aliveIds: Set<String>, compacted: MutableSet<String>, stop: () -> Boolean, tick: () -> Unit): BackupEngine.Result {
        if (stop()) return BackupEngine.Result(upToDate = work.upToDate, excluded = work.excluded, stopped = true)
        val folder = state.config.cloudDeviceFolder ?: return BackupEngine.Result(problem = BackupEngine.Problem.CLOUD_GONE)
        val path = arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER, folder)

        try {
            CloudClient.ensureFolder(app, ref, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupEngine.Result(problem = problemFor(app, e))
        }
        val listing = ArrayList<CloudEntry>()
        try {
            listing += CloudClient.list(app, ref, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupEngine.Result(problem = problemFor(app, e))
        }
        // Past the cap a stale `-wal` may be missing from the listing and survive beside its new main file.
        if (CloudArgs.mayBeTruncated(listing)) Log.w(TAG, "the backup folder's listing reached the cap; a stale sidecar beyond it is not seen")

        var stop: BackupEngine.Problem? = null
        var copied = 0
        var held = 0
        var missing = 0
        var failed = 0
        var stopped = false
        fun stopHere(): Boolean { if (!stopped && stop()) { stopped = true; Slog.d(TAG) { "cloud leg stopped by the person" } }; return stopped }
        for (candidate in work.toCopy) {
            if (stop != null || stopHere()) break
            val source = SoilFiles.itemFile(app, candidate.id)
            when {
                !source.exists() || source.length() == 0L -> missing++
                OpenFiles.isOpen(source) -> held++
                else -> {
                    if (compacted.add(candidate.id)) BackupEngine.compactPass(app, candidate.id, source)
                    when (val sent = send(app, ref, path, source, BackupPredicates.itemName(candidate.id), candidate.id, listing)) {
                        is Sent.Ok -> { copied++; state.update { it.copy(cloudStamps = it.cloudStamps + (candidate.id to candidate.updatedAt)) } }
                        is Sent.Refused -> failed++
                        is Sent.Stopped -> { failed++; stop = sent.problem }
                    }
                }
            }
            tick()
        }

        var storesCopied = 0
        var storesFailed = 0
        for (store in stores) {
            if (stop != null || stopHere()) break
            if (store.file.length() == 0L) { Slog.d(TAG) { "a store is empty; nothing to upload" }; tick(); continue }
            AppStores.checkpointIfOpen(store.name)
            when (val sent = send(app, ref, path, store.file, store.file.name, store.fileId, listing)) {
                is Sent.Ok -> storesCopied++
                is Sent.Refused -> storesFailed++
                is Sent.Stopped -> { storesFailed++; stop = sent.problem }
            }
            tick()
        }

        var indexCopied = false
        if (stop == null && !stopHere()) {
            if (SoilIndex.isReady()) SoilDb.checkpoint(SoilIndex.db())
            when (val sent = send(app, ref, path, SoilFiles.indexFile(app), BackupPredicates.INDEX_NAME, KeyMaterial.INDEX_FILE_ID, listing)) {
                is Sent.Ok -> indexCopied = true
                is Sent.Refused -> Unit
                is Sent.Stopped -> stop = sent.problem
            }
            tick()
        }

        val result = BackupEngine.Result(problem = stop, copied = copied, upToDate = work.upToDate, excluded = work.excluded, held = held, missing = missing, failed = failed, storesCopied = storesCopied, storesFailed = storesFailed, indexCopied = indexCopied, stopped = stopped)
        if (result.succeeded && !stopped) {
            state.update {
                it.copy(cloudLastRunAt = System.currentTimeMillis(), cloudLastCopied = result.copied, cloudLastSkipped = result.upToDate + result.excluded + result.held + result.missing, cloudStamps = BackupPredicates.pruneStamps(it.cloudStamps, aliveIds))
            }
        }
        Slog.d(TAG) { "cloud: $copied copied, ${result.upToDate} up to date, ${result.excluded} excluded, $held held, $missing missing, $failed failed, stores $storesCopied/$storesFailed, index=$indexCopied, stoppedBy=${if (stopped) "person" else if (stop != null) "failure" else "nothing"}" }
        return result
    }

    private suspend fun send(app: Context, ref: Extension, path: Array<String>, live: File, name: String, fileId: String, listing: MutableList<CloudEntry>): Sent {
        val snapshot = withContext(Dispatchers.IO) { SelfContainedSnapshot.of(app, live, name, fileId) } ?: return Sent.Refused
        val bytes = snapshot.length()
        val pfd = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() } ?: return Sent.Refused
        val entry = try {
            CloudClient.upload(app, ref, path, name, MIME, pfd, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Sent.Stopped(problemFor(app, e))
        }
        listing.removeAll { it.name == name && !it.isFolder }
        listing.add(entry)
        if (ExportVerification.cloudVerdict(entry.sizeBytes, bytes) != ExportVerification.Verdict.OK) {
            Slog.d(TAG) { "upload not corroborated: sent $bytes B, reported ${entry.sizeBytes} B; kept, retried next run" }
            return Sent.Refused
        }
        return dropStaleSidecar(app, ref, name, listing)
    }

    private suspend fun dropStaleSidecar(app: Context, ref: Extension, name: String, listing: MutableList<CloudEntry>): Sent {
        val stale = CloudBackupRules.staleSidecar(listing, name) ?: return Sent.Ok
        return try {
            CloudClient.delete(app, ref, stale.id)
            listing.remove(stale)
            Slog.d(TAG) { "a stale sidecar was removed before the stamp" }
            Sent.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Sent.Stopped(problemFor(app, e))
        }
    }

    private fun problemFor(app: Context, e: Exception): BackupEngine.Problem {
        val failure = when (e) {
            is CloudNotConnected -> CloudBackupRules.Failure.NOT_CONNECTED
            is CloudNetworkFailed -> CloudBackupRules.Failure.NETWORK
            is ExtensionCallFailed -> if (CloudProviders.installed(app) == null) CloudBackupRules.Failure.GONE else CloudBackupRules.Failure.UNANSWERED
            else -> { Log.w(TAG, "cloud leg failed unexpectedly", e); CloudBackupRules.Failure.UNANSWERED }
        }
        Slog.d(TAG) { "cloud leg stopping: $failure" }
        return CloudBackupRules.problemFor(failure)
    }
}
