package com.symmetricalpalmtree.soil.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.symmetricalpalmtree.soil.BuildConfig
import com.symmetricalpalmtree.soil.cloud.CloudProviders
import com.symmetricalpalmtree.soil.crypto.GlobalRotation
import com.symmetricalpalmtree.soil.crypto.KeyOpener
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.OpenFiles
import com.symmetricalpalmtree.soil.crypto.RotationPlan
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.data.FileKey
import com.symmetricalpalmtree.soil.data.SoilDb
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **One manual backup run**, two legs: the local one (the persisted SAF tree, with `dev/` inside
 * it on a debug build) and the cloud one (`Backups/<device folder>/` in the provider's tree,
 * [CloudBackupLeg]). Which legs exist is decided from the config at run start; discovery is
 * re-asked, never trusted stale. Local first, then cloud; neither, and the run does nothing and
 * says so. The two legs share only the WAL absorb: whichever reaches a file first pays for it.
 *
 * The local leg's order is the design: the work list over every alive item and the stamp map;
 * per item, a file an app holds open is skipped and counted, the WAL is absorbed through one open,
 * the file is copied atomically, a still-live WAL alongside, and the stamp written per success
 * with the `updatedAt` the work list read; every app store after the items, every pass, no
 * stamps; the index last, checkpointed, snapshotted and probed before it streams. Nothing here
 * bumps an item's `updatedAt`. Headless IO that never throws: every failure is a count or a
 * [Problem].
 */
object BackupEngine {

    private const val TAG = "BackupEngine"
    private const val DIR = "backup"

    enum class Problem {
        /** Neither leg exists: no folder chosen and no cloud destination set up. */
        NO_DESTINATION,
        /** The chosen folder no longer resolves. */
        FOLDER_GONE,
        /** No key in session. */
        NO_KEY,
        /** A rotation marker stands: the library is in two keys, and a copy taken now could be under either. */
        ROTATION_PENDING,
        CLOUD_NOT_CONNECTED,
        CLOUD_NETWORK,
        CLOUD_UNANSWERED,
        CLOUD_GONE,
    }

    data class Result(
        val problem: Problem? = null,
        val copied: Int = 0,
        val upToDate: Int = 0,
        val excluded: Int = 0,
        /** Items skipped because an app holds the file open. */
        val held: Int = 0,
        /** Index rows whose file is missing from the garden: skipped, not failed. */
        val missing: Int = 0,
        val failed: Int = 0,
        val storesCopied: Int = 0,
        val storesFailed: Int = 0,
        val indexCopied: Boolean = false,
    ) {
        /** At least one destination write landed. */
        val succeeded: Boolean get() = copied > 0 || storesCopied > 0 || indexCopied
    }

    /** One result per leg; a leg that did not run is null, never a zero result. */
    data class Outcome(val local: Result? = null, val cloud: Result? = null, val problem: Problem? = null)

    enum class Leg { LOCAL, CLOUD }

    data class Progress(val done: Int, val total: Int, val leg: Leg = Leg.LOCAL)

    suspend fun run(context: Context, onProgress: (Progress) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        try {
            runInner(context.applicationContext, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "backup run failed", e)
            Outcome(local = Result(failed = 1))
        }
    }

    private suspend fun runInner(app: Context, onProgress: (Progress) -> Unit): Outcome {
        if (!SoilIndex.isReady() || KeySession.get() == null) return Outcome(problem = Problem.NO_KEY)
        if (GlobalRotation.hasMarker(app)) return Outcome(problem = Problem.ROTATION_PENDING)
        val store = BackupStore()
        val state = RunState(store.read(), store)

        val cloudRef = if (state.config.cloudEnabled) CloudProviders.installed(app) else null
        val legs = CloudBackupRules.legs(
            hasFolder = state.config.treeUri != null,
            cloudEnabled = state.config.cloudEnabled,
            hasProvider = cloudRef != null,
            hasDeviceFolder = state.config.cloudDeviceFolder != null,
        )
        if (legs.none) return Outcome(problem = Problem.NO_DESTINATION)

        val items = IndexStore().aliveItems()
        val candidates = items.map { BackupPredicates.Candidate(it.id, it.updatedAt, it.flags) }
        val aliveIds = items.mapTo(HashSet()) { it.id }
        val stores = SoilFiles.storeNames(app).map { StoreFile(it, SoilFiles.storeFile(app, it)) }

        val localWork = if (legs.local) BackupPredicates.workList(candidates, state.config.stamps) else null
        val cloudWork = if (legs.cloud) BackupPredicates.workList(candidates, state.config.cloudStamps) else null
        val total = CloudBackupRules.total(
            localWork?.let { CloudBackupRules.units(it.toCopy.size, stores.size) } ?: 0,
            cloudWork?.let { CloudBackupRules.units(it.toCopy.size, stores.size) } ?: 0,
        )
        var done = 0
        var leg = if (legs.local) Leg.LOCAL else Leg.CLOUD
        val tick = { done++; onProgress(Progress(done, total, leg)) }
        onProgress(Progress(0, total, leg))

        val compacted = HashSet<String>()
        val local = localWork?.let { runLocalLeg(app, state, it, stores, aliveIds, compacted, tick) }
        val cloud = if (cloudWork != null && cloudRef != null) {
            leg = Leg.CLOUD
            CloudBackupLeg.run(app, cloudRef, state, cloudWork, stores, aliveIds, compacted, tick)
        } else null

        Slog.d(TAG) { "run: local=${local != null} cloud=${cloud != null} of $total units" }
        return Outcome(local = local, cloud = cloud)
    }

    /** One app store: its name (the file stem) and its file. */
    class StoreFile(val name: String, val file: File) {
        val fileId: String get() = RotationPlan.storeId(name)
    }

    private fun runLocalLeg(app: Context, state: RunState, work: BackupPredicates.WorkList, stores: List<StoreFile>, aliveIds: Set<String>, compacted: MutableSet<String>, tick: () -> Unit): Result {
        val treeUri = state.config.treeUri ?: return Result(problem = Problem.FOLDER_GONE)
        val writer = SafBackupWriter(app.contentResolver, Uri.parse(treeUri))
        val root = writer.root() ?: return Result(problem = Problem.FOLDER_GONE)
        val dest = if (BuildConfig.DEBUG) writer.ensureDir(root, BackupPredicates.DEV_SUBDIR) ?: return Result(problem = Problem.FOLDER_GONE) else root

        var copied = 0
        var held = 0
        var missing = 0
        var failed = 0
        for (candidate in work.toCopy) {
            val source = SoilFiles.itemFile(app, candidate.id)
            when {
                !source.exists() || source.length() == 0L -> missing++
                OpenFiles.isOpen(source) -> held++
                else -> {
                    if (compacted.add(candidate.id)) compactPass(app, candidate.id, source)
                    if (copyItem(writer, dest, candidate.id, source)) {
                        copied++
                        // Per success, immediately: a kill mid-run keeps every stamp earned.
                        state.update { it.copy(stamps = it.stamps + (candidate.id to candidate.updatedAt)) }
                    } else failed++
                }
            }
            tick()
        }

        var storesCopied = 0
        var storesFailed = 0
        for (store in stores) {
            when {
                store.file.length() == 0L -> Slog.d(TAG) { "a store is empty; nothing to copy" }
                copyStore(app, writer, dest, store) -> storesCopied++
                else -> storesFailed++
            }
            tick()
        }

        val indexCopied = copyIndex(app, writer, dest)
        tick()

        val result = Result(copied = copied, upToDate = work.upToDate, excluded = work.excluded, held = held, missing = missing, failed = failed, storesCopied = storesCopied, storesFailed = storesFailed, indexCopied = indexCopied)
        if (result.succeeded) {
            state.update {
                it.copy(lastRunAt = System.currentTimeMillis(), lastCopied = result.copied, lastSkipped = result.upToDate + result.excluded + result.held + result.missing, stamps = BackupPredicates.pruneStamps(it.stamps, aliveIds))
            }
        }
        Slog.d(TAG) { "local: $copied copied, ${result.upToDate} up to date, ${result.excluded} excluded, $held held, $missing missing, $failed failed, stores $storesCopied/$storesFailed, index=$indexCopied" }
        return result
    }

    /**
     * Fold a live WAL into the file about to travel, through one open under the cached key, so
     * the main file alone is a complete copy. Best effort: a file that will not open is still
     * copied as the bytes it is, its WAL alongside. An app's own purge ran at its close; nothing
     * here rewrites a row.
     */
    internal fun compactPass(context: Context, itemId: String, source: File) {
        val wal = File(source.path + BackupPredicates.WAL_SUFFIX)
        if (!wal.exists() || wal.length() == 0L) return
        val passphrase = KeySession.get() ?: return
        val db = try {
            when (val key = KeyOpener.keyFor(context, itemId, source, passphrase)) {
                is FileKey.Raw -> SoilCrypto.openRawKey(source, key.value)
                is FileKey.Passphrase -> SoilCrypto.openRaw(source, key.value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "compact pass could not open; copying as-is: ${e.javaClass.simpleName}")
            return
        }
        try {
            SoilDb.checkpoint(db)
        } finally {
            runCatching { db.close() }
        }
    }

    /**
     * The destination's `<name>-wal` goes first, verifiably: a main file written beside a stale WAL
     * would have that WAL replayed into it. Then the item file, then a still-live WAL alongside.
     */
    private fun copyItem(writer: SafBackupWriter, dest: Uri, itemId: String, source: File): Boolean {
        val name = BackupPredicates.itemName(itemId)
        val walName = name + BackupPredicates.WAL_SUFFIX
        if (!dropDestWal(writer, dest, walName)) return false
        if (!writer.writeAtomic(dest, name, source)) return false
        val wal = File(source.path + BackupPredicates.WAL_SUFFIX)
        return if (wal.exists() && wal.length() > 0L) writer.writeAtomic(dest, walName, wal) else true
    }

    /** Delete [walName] (and a writer's `.old` of it) from [dest]; false when the listing or a delete failed. */
    private fun dropDestWal(writer: SafBackupWriter, dest: Uri, walName: String): Boolean {
        val entries = writer.list(dest) ?: return false
        val oldName = walName + BackupPredicates.OLD_SUFFIX
        return entries.filter { !it.isDir && (it.name == walName || it.name == oldName) }.all { writer.delete(it.uri) }
    }

    private fun copyIndex(context: Context, writer: SafBackupWriter, dest: Uri): Boolean {
        if (SoilIndex.isReady()) SoilDb.checkpoint(SoilIndex.db())
        return copyDatabase(context, writer, dest, SoilFiles.indexFile(context), BackupPredicates.INDEX_NAME)
    }

    private fun copyStore(context: Context, writer: SafBackupWriter, dest: Uri, store: StoreFile): Boolean {
        AppStores.checkpointIfOpen(store.name)
        return copyDatabase(context, writer, dest, store.file, store.file.name)
    }

    /**
     * The live-database copy the index and every store take: snapshot into the cache, probe the
     * snapshot, stream that; a non-empty post-checkpoint WAL is snapshotted and written alongside.
     * Only a failed snapshot streams the live file.
     */
    private fun copyDatabase(context: Context, writer: SafBackupWriter, dest: Uri, live: File, destName: String): Boolean {
        val liveWal = File(live.path + BackupPredicates.WAL_SUFFIX)
        val dir = File(context.cacheDir, DIR)
        var snapshot: File? = null
        var walSnapshot: File? = null
        try {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw java.io.IOException("could not create the backup cache directory")
            val snap = File(dir, destName)
            live.copyTo(snap, overwrite = true)
            if (liveWal.exists() && liveWal.length() > 0L) {
                val walSnap = File(dir, destName + BackupPredicates.WAL_SUFFIX)
                liveWal.copyTo(walSnap, overwrite = true)
                walSnapshot = walSnap
            }
            snapshot = snap.takeIf { it.length() > 0L && it.length() == live.length() && SoilCrypto.probe(it) == SoilFileKind.Encrypted }
                ?: throw java.io.IOException("snapshot failed its probe")
        } catch (e: Exception) {
            Log.w(TAG, "snapshot of $destName failed; falling back to the live file", e)
            snapshot = null
            walSnapshot = null
        }
        val walName = destName + BackupPredicates.WAL_SUFFIX
        // The stale destination WAL first: a main file written beside it would have it replayed in. A delete that fails skips the write.
        val mainOk = dropDestWal(writer, dest, walName) && writer.writeAtomic(dest, destName, snapshot ?: live)
        val walSource = walSnapshot ?: liveWal.takeIf { snapshot == null && it.exists() && it.length() > 0L }
        val walOk = when {
            !mainOk -> false
            walSource != null -> writer.writeAtomic(dest, walName, walSource)
            else -> true
        }
        runCatching { dir.deleteRecursively() }
        return mainOk && walOk
    }
}

/** The run's config, moved forward as each stamp is earned; a write that fails only re-copies next run. */
internal class RunState(var config: BackupConfig, private val store: BackupStore) {
    fun update(change: (BackupConfig) -> BackupConfig) {
        config = change(config)
        runCatching { store.write(config) }.onFailure { Log.w("BackupEngine", "config write failed", it) }
    }
}
