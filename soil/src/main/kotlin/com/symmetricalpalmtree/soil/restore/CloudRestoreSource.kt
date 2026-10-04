package com.symmetricalpalmtree.soil.restore

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.soil.backup.BackupPredicates
import com.symmetricalpalmtree.soil.cloud.CloudClient
import com.symmetricalpalmtree.soil.cloud.CloudNetworkFailed
import com.symmetricalpalmtree.soil.cloud.CloudNotConnected
import com.symmetricalpalmtree.soil.cloud.CloudProviders
import com.symmetricalpalmtree.soil.cloud.CloudTimeouts
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.ExtensionCallFailed
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The cloud source: `Backups/<device folder>/` under the provider's root, read through `list` and
 * `download` only. The handle is the folder's name; the fetch re-lists it. No `-wal` is ever
 * fetched. A mid-fetch failure aborts the whole restore. The four failures map as the backup leg's.
 */
class CloudRestoreSource(private val app: Context, private val ref: Extension) : RestoreSource {

    override suspend fun listBackups(): ListResult = withContext(Dispatchers.IO) {
        val folders = try {
            CloudClient.list(app, ref, arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ListResult.Failed(problemFor(e))
        }
        val found = ArrayList<RestoreBackup>()
        var skipped = 0
        var lastProblem: RestoreProblem? = null
        for (folder in RestoreRows.deviceFolders(folders.map(::listed))) {
            val entries = try {
                CloudClient.list(app, ref, arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER, folder.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNotConnected) {
                return@withContext ListResult.Failed(RestoreProblem.CloudNotConnected)
            } catch (e: CloudNetworkFailed) {
                return@withContext ListResult.Failed(RestoreProblem.CloudNetwork)
            } catch (e: Exception) {
                skipped++
                lastProblem = problemFor(e)
                continue
            }
            RestoreRows.rowFor(folder.name, entries.map(::listed), RestoreLeg.CLOUD, folder.name)?.let(found::add)
        }
        Slog.d(TAG) { "enumerated ${found.size} backup(s) in the cloud, $skipped folder(s) skipped" }
        when {
            found.isNotEmpty() -> ListResult.Backups(found)
            lastProblem != null -> ListResult.Failed(lastProblem)
            else -> ListResult.Failed(RestoreProblem.NotABackup)
        }
    }

    override suspend fun fetchInto(backup: RestoreBackup, staging: File, onProgress: (done: Int, total: Int) -> Unit): FetchResult = withContext(Dispatchers.IO) {
        val path = arrayOf(BackupPredicates.CLOUD_BACKUPS_FOLDER, backup.handle)
        val entries = try {
            CloudClient.list(app, ref, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext FetchResult.Failed(problemFor(e))
        }
        val manifest = RestoreManifest.plan(entries.map(::listed), RestoreLeg.CLOUD) ?: return@withContext FetchResult.Failed(RestoreProblem.NotABackup)
        val byName = entries.filter { !it.isFolder }.associateBy { it.name }
        val total = manifest.items.size
        var done = 0
        for (item in manifest.items) {
            val entry = byName[item.name] ?: return@withContext FetchResult.Failed(RestoreProblem.FetchFailed(item.name))
            val target = RestoreStaging.targetFor(staging, item)
            var failure: RestoreProblem? = null
            val ok = RestoreStaging.writeStagedVia(target, item.size) { part ->
                val pfd = runCatching { ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE) }.getOrNull()
                if (pfd == null) { Log.w(TAG, "could not open a staging part for writing"); return@writeStagedVia -1L }
                try {
                    CloudClient.download(app, ref, entry.id, pfd, CloudTimeouts.downloadBudgetMs(item.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = problemFor(e)
                    -1L
                }
            }
            if (!ok) return@withContext FetchResult.Failed(failure ?: RestoreProblem.FetchFailed(item.name))
            done++
            onProgress(done, total)
        }
        Slog.d(TAG) { "staged $done file(s) from the cloud, ${manifest.totalBytes} B planned" }
        FetchResult.Staged(manifest)
    }

    private fun listed(entry: CloudEntry): Listed = Listed(entry.name, entry.sizeBytes, entry.isFolder, entry.modifiedAt)

    private fun problemFor(e: Exception): RestoreProblem {
        val problem = when (e) {
            is CloudNotConnected -> RestoreProblem.CloudNotConnected
            is CloudNetworkFailed -> RestoreProblem.CloudNetwork
            is ExtensionCallFailed -> if (CloudProviders.installed(app) == null) RestoreProblem.CloudGone else RestoreProblem.CloudUnanswered
            else -> { Log.w(TAG, "cloud restore source failed unexpectedly", e); RestoreProblem.CloudUnanswered }
        }
        Slog.d(TAG) { "cloud source problem: ${problem.javaClass.simpleName}" }
        return problem
    }

    private companion object { const val TAG = "CloudRestoreSource" }
}
