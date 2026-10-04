package com.symmetricalpalmtree.soil.restore

import android.content.ContentResolver
import android.net.Uri
import com.symmetricalpalmtree.soil.backup.SafBackupReader
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The local source: a picked tree, one level deep (the tree itself, and each subfolder holding an index, which is what finds a debug build's `dev/`). */
class SafRestoreSource(private val reader: SafBackupReader) : RestoreSource {

    constructor(resolver: ContentResolver, treeUri: Uri) : this(SafBackupReader(resolver, treeUri))

    override suspend fun listBackups(): ListResult = withContext(Dispatchers.IO) {
        val root = reader.root() ?: return@withContext ListResult.Failed(RestoreProblem.SourceUnreachable)
        val rootEntries = reader.list(root) ?: return@withContext ListResult.Failed(RestoreProblem.ListingFailed)
        val found = ArrayList<RestoreBackup>()
        backupOf(rootEntries, reader.rootName() ?: DEFAULT_NAME, root)?.let(found::add)
        for (sub in rootEntries.filter { it.isDir }.sortedBy { it.name }) {
            val subEntries = reader.list(sub.uri) ?: run { Slog.d(TAG) { "skipping an unreadable subfolder" }; continue }
            backupOf(subEntries, sub.name, sub.uri)?.let(found::add)
        }
        Slog.d(TAG) { "enumerated ${found.size} backup(s) one level deep" }
        if (found.isEmpty()) ListResult.Failed(RestoreProblem.NotABackup) else ListResult.Backups(found)
    }

    override suspend fun fetchInto(backup: RestoreBackup, staging: File, onProgress: (done: Int, total: Int) -> Unit): FetchResult = withContext(Dispatchers.IO) {
        val dir = Uri.parse(backup.handle)
        val entries = reader.list(dir) ?: return@withContext FetchResult.Failed(RestoreProblem.ListingFailed)
        val manifest = RestoreManifest.plan(entries.map(::listed), RestoreLeg.LOCAL) ?: return@withContext FetchResult.Failed(RestoreProblem.NotABackup)
        val byName = entries.associateBy { it.name }
        val total = manifest.items.size
        var done = 0
        for (item in manifest.items) {
            val entry = byName[item.name] ?: return@withContext FetchResult.Failed(RestoreProblem.FetchFailed(item.name))
            val target = RestoreStaging.targetFor(staging, item)
            val ok = reader.open(entry.uri)?.use { input -> RestoreStaging.writeStaged(target, item.size) { out -> input.copyTo(out) } } ?: false
            if (!ok) return@withContext FetchResult.Failed(RestoreProblem.FetchFailed(item.name))
            done++
            onProgress(done, total)
        }
        Slog.d(TAG) { "staged $done file(s), ${manifest.totalBytes} B planned" }
        FetchResult.Staged(manifest)
    }

    private fun backupOf(entries: List<SafBackupReader.Entry>, name: String, dirUri: Uri): RestoreBackup? =
        RestoreRows.rowFor(name, entries.map(::listed), RestoreLeg.LOCAL, dirUri.toString())

    private fun listed(entry: SafBackupReader.Entry): Listed = Listed(entry.name, entry.size, entry.isDir, entry.lastModified)

    private companion object {
        const val TAG = "SafRestoreSource"
        const val DEFAULT_NAME = "Backup"
    }
}
