package com.symmetricalpalmtree.soil.restore

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.backup.BackupPredicates
import com.symmetricalpalmtree.soil.backup.BackupStore
import com.symmetricalpalmtree.soil.crypto.AttemptLimiter
import com.symmetricalpalmtree.soil.crypto.GlobalKey
import com.symmetricalpalmtree.soil.crypto.GlobalRotation
import com.symmetricalpalmtree.soil.crypto.KeyMaterial
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.crypto.RealRekeyFs
import com.symmetricalpalmtree.soil.crypto.RekeyNames
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The commit engine**: stage a chosen backup, prove it, replace the library with it. The doors
 * in the order the screen walks them, and nothing live is touched before [commit]:
 *
 *  1. [preflight]: refused while a rotation marker stands, while an app holds an item, or when
 *     the listing's bytes plus headroom will not fit.
 *  2. [stage]: the source fetches every manifest item beside the live library.
 *  3. [validate]: every staged main file of the asked kinds probes as encrypted SQLite.
 *  4. [proveCached] / [proveTyped]: the staged index must open before anything is committed.
 *  5. [pruneOrphans]: with the key proven, the staged index says which items the backup is; a
 *     staged file it does not name, and a store that opens under neither key, are left out and
 *     named, never installed.
 *  6. [commit]: blind the process, close every store and the index, aside by rename, install by
 *     rename with the index last as the marker, key state, discard the aside. A failed swap
 *     renames the aside back. The caller reopens the index and relaunches Home either way.
 *
 * Every door catches at its top and answers a [Problem]. No passphrase is logged, put in an
 * Intent, or written anywhere but the passphrase store. [recoverInterrupted] is the launch-time
 * twin, run before the index is looked at.
 */
object RestoreEngine {

    const val ASIDE_DIR = "restore_replaced"
    const val LIMITER_KEY = AttemptLimiter.RESTORE_KEY

    sealed class Problem {
        object RotationPending : Problem()
        object ItemHeld : Problem()
        data class NotEnoughSpace(val shortfallBytes: Long) : Problem()
        data class Source(val problem: RestoreProblem) : Problem()
        data class InvalidFile(val fileName: String) : Problem()
        object ParkFailed : Problem()
        data class SwapFailed(val step: Char) : Problem()
        data class Unexpected(val what: String) : Problem()
    }

    sealed class StageResult {
        data class Staged(val manifest: RestoreManifest) : StageResult()
        data class Failed(val problem: Problem) : StageResult()
    }

    sealed class Outcome {
        /** [missing]: item files the backup's index names that the backup did not carry; their rows are installed with no file. */
        data class Committed(val items: Int, val stores: Int, val leftOut: List<String> = emptyList(), val missing: List<String> = emptyList()) : Outcome()
        /** Refused before the point of no return; the live library was never touched. */
        data class Refused(val problem: Problem) : Outcome()
        /** The swap failed and was renamed back; the live library is whole; the index is closed. */
        data class RolledBack(val problem: Problem) : Outcome()
        /** The restored index landed but the key step threw; the relaunch may stop at Unlock. */
        data class Interrupted(val problem: Problem) : Outcome()
    }

    // ── 1. Pre-flight ──────

    suspend fun preflight(context: Context, backup: RestoreBackup): Problem? = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            when {
                GlobalRotation.hasMarker(app) -> Problem.RotationPending
                ItemSessions.openItems().isNotEmpty() -> Problem.ItemHeld
                else -> spaceProblem(backup.totalBytes, RestoreStaging.usableBytes(app))
            }
        } catch (e: Exception) {
            Log.w(TAG, "preflight failed", e)
            Problem.Unexpected(e.javaClass.simpleName)
        }
    }

    fun spaceProblem(totalBytes: Long, usableBytes: Long, headroom: Long = RestoreStaging.HEADROOM_BYTES): Problem? {
        if (RestoreStaging.fits(totalBytes, usableBytes, headroom)) return null
        val need = (if (totalBytes < 0L) 0L else totalBytes) + headroom
        val have = if (usableBytes < 0L) 0L else usableBytes
        return Problem.NotEnoughSpace(shortfallBytes = (need - have).coerceAtLeast(1L))
    }

    /** After a failed fetch: the disk is named when it is the disk, else the source's problem. */
    fun fetchFailureProblem(sourceProblem: Problem, totalBytes: Long, stagedBytes: Long, usableBytes: Long, headroom: Long = RestoreStaging.HEADROOM_BYTES): Problem {
        if (usableBytes < 0L) return sourceProblem
        val remaining = if (totalBytes < 0L) 0L else (totalBytes - stagedBytes).coerceAtLeast(0L)
        return spaceProblem(remaining, usableBytes, headroom) ?: sourceProblem
    }

    fun headroomProblem(usableBytes: Long, headroom: Long = RestoreStaging.HEADROOM_BYTES): Problem? = spaceProblem(0L, usableBytes, headroom)

    // ── 2. Stage ──────

    suspend fun stage(context: Context, source: RestoreSource, backup: RestoreBackup, onProgress: (done: Int, total: Int) -> Unit): StageResult = withContext(Dispatchers.IO) {
        try {
            val staging = RestoreStaging.reset(context)
            when (val r = source.fetchInto(backup, staging, onProgress)) {
                is FetchResult.Staged -> StageResult.Staged(r.manifest)
                is FetchResult.Failed -> {
                    val problem = fetchFailureProblem(Problem.Source(r.problem), backup.totalBytes, RestoreStaging.stagedBytes(staging), RestoreStaging.usableBytes(context))
                    RestoreStaging.discard(context)
                    StageResult.Failed(problem)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stage failed", e)
            runCatching { RestoreStaging.discard(context) }
            StageResult.Failed(Problem.Unexpected(e.javaClass.simpleName))
        }
    }

    // ── 3. Validate ──────

    val ALL_KINDS: Set<ItemKind> = ItemKind.values().toSet()
    val INDEX_ONLY: Set<ItemKind> = setOf(ItemKind.INDEX, ItemKind.INDEX_WAL)
    val ITEMS: Set<ItemKind> = setOf(ItemKind.SOIL, ItemKind.SOIL_WAL)

    suspend fun validate(context: Context, manifest: RestoreManifest, only: Set<ItemKind> = ALL_KINDS): Problem? = withContext(Dispatchers.IO) {
        try {
            validationProblem(RestoreStaging.dir(context), manifest, SoilCrypto::probe, only)
        } catch (e: Exception) {
            Log.w(TAG, "validate failed", e)
            Problem.Unexpected(e.javaClass.simpleName)
        }
    }

    /** Pure over a [probe]: every main file of a kind in [only] must exist, weigh what the listing said, and probe encrypted; a WAL must be present. */
    fun validationProblem(stagingDir: File, manifest: RestoreManifest, probe: (File) -> SoilFileKind, only: Set<ItemKind> = ALL_KINDS): Problem? {
        for (item in manifest.items) {
            if (item.kind !in only) continue
            val file = RestoreStaging.targetFor(stagingDir, item)
            if (!file.isFile) return Problem.InvalidFile(item.name)
            if (item.size >= 0L && file.length() != item.size) return Problem.InvalidFile(item.name)
            when (item.kind) {
                ItemKind.INDEX, ItemKind.SOIL, ItemKind.STORE -> if (probe(file) != SoilFileKind.Encrypted) return Problem.InvalidFile(item.name)
                ItemKind.INDEX_WAL, ItemKind.SOIL_WAL, ItemKind.STORE_WAL -> Unit
            }
        }
        return null
    }

    // ── 4. Prove the key ──────

    fun stagedIndex(context: Context): File = File(RestoreStaging.dir(context), RestoreManifest.INDEX_NAME)

    /** This device's cached global against the staged index, silently. */
    suspend fun proveCached(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val cached = PassphraseStore.getGlobalPassphrase(context.applicationContext) ?: return@withContext null
            if (SoilCrypto.verifyPassphrase(stagedIndex(context), cached)) cached else null
        } catch (e: Exception) {
            Log.w(TAG, "cached-key proof failed", e)
            null
        }
    }

    /** What the person typed: as typed first, then normalized as a recovery key. Recorded in the RESTORE limiter bucket. */
    suspend fun proveTyped(context: Context, typed: String): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val file = stagedIndex(context)
            val proven = when {
                SoilCrypto.verifyPassphrase(file, typed) -> typed
                else -> GlobalKey.normalize(typed).takeIf { it != typed && SoilCrypto.verifyPassphrase(file, it) }
            }
            if (proven != null) AttemptLimiter.recordSuccess(app, LIMITER_KEY) else AttemptLimiter.recordFailure(app, LIMITER_KEY)
            proven
        } catch (e: Exception) {
            Log.w(TAG, "typed-key proof failed", e)
            AttemptLimiter.recordFailure(app, LIMITER_KEY)
            null
        }
    }

    // ── 5. Orphans ──────

    sealed class PruneResult {
        data class Pruned(val manifest: RestoreManifest, val leftOut: List<String>, val missing: List<String> = emptyList()) : PruneResult()
        data class Failed(val problem: Problem) : PruneResult()
    }

    /** Read the staged index's alive item ids under [proven]; drop every staged item file it does not name and every store that is not encrypted SQLite or does not open under the key, read-only. */
    suspend fun pruneOrphans(context: Context, manifest: RestoreManifest, proven: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): PruneResult = withContext(Dispatchers.IO) {
        try {
            val staging = RestoreStaging.dir(context)
            val (alive, expected) = aliveItemIds(stagedIndex(context), proven)
            // An alive row the backup should carry but does not (held open or missing at backup time) installs with nothing behind it: named, never silent.
            val missing = missingItems(manifest, expected)
            if (missing.isNotEmpty()) Log.w(TAG, "the staged index names ${missing.size} item(s) the backup does not carry: $missing")
            val stores = manifest.items.filter { it.kind == ItemKind.STORE }
            val deadStores = HashSet<String>()
            stores.forEachIndexed { i, item ->
                onProgress(i, stores.size)
                val f = RestoreStaging.targetFor(staging, item)
                // Read-only: a read-write open's close would checkpoint a staged WAL into the main file.
                val dead = !f.isFile || SoilCrypto.probe(f) != SoilFileKind.Encrypted || !SoilCrypto.verifyPassphraseReadOnly(f, proven)
                File(f.path + SHM).delete()
                if (dead) deadStores.add(item.name)
            }
            onProgress(stores.size, stores.size)
            val (kept, leftOut) = orphanRule(manifest, alive, deadStores)
            for (item in manifest.items) {
                if (item in kept.items) continue
                val f = RestoreStaging.targetFor(staging, item)
                if (f.exists() && !f.delete()) Log.w(TAG, "orphan ${item.name} could not be deleted from staging")
            }
            if (leftOut.isNotEmpty()) Log.w(TAG, "left out ${leftOut.size} orphan(s): $leftOut")
            PruneResult.Pruned(kept, leftOut, missing)
        } catch (e: Exception) {
            Log.w(TAG, "orphan prune failed", e)
            PruneResult.Failed(Problem.Unexpected(e.javaClass.simpleName))
        }
    }

    /** The item files [expectedIds] name that the manifest does not carry, sorted. */
    fun missingItems(manifest: RestoreManifest, expectedIds: Set<String>): List<String> {
        val staged = manifest.items.filter { it.kind == ItemKind.SOIL }.mapTo(HashSet()) { soilStem(it.name) }
        return (expectedIds - staged).map { it + SoilFiles.ITEM_SUFFIX }.sorted()
    }

    fun orphanRule(manifest: RestoreManifest, aliveIds: Set<String>, deadStores: Set<String> = emptySet()): Pair<RestoreManifest, List<String>> {
        val leftOut = ArrayList<String>()
        val kept = manifest.items.filter { item ->
            when (item.kind) {
                ItemKind.SOIL -> (soilStem(item.name) in aliveIds).also { if (!it) leftOut.add(item.name) }
                ItemKind.SOIL_WAL -> soilStem(item.name.removeSuffix(WAL)) in aliveIds
                ItemKind.STORE -> (item.name !in deadStores).also { if (!it) leftOut.add(item.name) }
                ItemKind.STORE_WAL -> item.name.removeSuffix(WAL) !in deadStores
                else -> true
            }
        }
        return RestoreManifest(kept) to leftOut.sorted()
    }

    private const val WAL = "-wal"
    private const val SHM = "-shm"

    private fun soilStem(name: String): String = name.removeSuffix(SoilFiles.ITEM_SUFFIX)

    /** Every alive item id, and those of them a backup copies (not excluded). */
    private fun aliveItemIds(index: File, proven: String): Pair<Set<String>, Set<String>> {
        val db = SoilCrypto.openRawReadOnly(index, proven)
        try {
            val ids = HashSet<String>()
            val expected = HashSet<String>()
            db.rawQuery("SELECT id, flags FROM item WHERE deletedAt IS NULL", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    ids.add(id)
                    if (!BackupPredicates.isExcluded(c.getInt(1))) expected.add(id)
                }
            }
            return ids to expected
        } finally {
            runCatching { db.close() }
            File(index.path + SHM).delete()
        }
    }

    // ── 6. Commit ──────

    /** Whole under [NonCancellable] on IO. After any outcome but [Outcome.Refused] the index is closed and the caller reopens it and relaunches. */
    suspend fun commit(context: Context, manifest: RestoreManifest, proven: String, leftOut: List<String> = emptyList(), missing: List<String> = emptyList()): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            val app = context.applicationContext
            try {
                commitInner(app, manifest, proven, leftOut, missing)
            } catch (e: Exception) {
                Log.e(TAG, "commit threw before the index closed", e)
                runCatching { RestoreDestination.clearPark(app) }
                runCatching { RestoreStaging.discard(app) }
                Outcome.Refused(Problem.Unexpected(e.javaClass.simpleName))
            }
        }

    private suspend fun commitInner(app: Context, manifest: RestoreManifest, proven: String, leftOut: List<String>, missing: List<String>): Outcome {
        val root = SoilFiles.root(app)
        val staging = RestoreStaging.dir(app)

        // Step 0: a torn staging set is caught here, not by a rename half-way through the install.
        // The index is exempt from the size rule: the proof opened it read-only, but SQLite may still touch it.
        for (item in manifest.items) {
            val f = RestoreStaging.targetFor(staging, item)
            val torn = when (item.kind) {
                ItemKind.INDEX -> !f.isFile
                ItemKind.INDEX_WAL -> false
                else -> !f.isFile || (item.size >= 0L && f.length() != item.size)
            }
            if (torn) { RestoreStaging.discard(root); return Outcome.Refused(Problem.InvalidFile(item.name)) }
        }
        if (GlobalRotation.hasMarker(app)) { RestoreStaging.discard(root); return Outcome.Refused(Problem.RotationPending) }
        if (ItemSessions.openItems().isNotEmpty()) { RestoreStaging.discard(root); return Outcome.Refused(Problem.ItemHeld) }

        // Step 1: this device's destination out of the live index, parked device-locally.
        val parked = RestoreDestination.parkedFrom(BackupStore().read())
        if (!RestoreDestination.park(app, parked)) return Outcome.Refused(Problem.ParkFailed)

        // Step 2: the honest re-measure; only the headroom is left to fit.
        headroomProblem(RestoreStaging.usableBytes(root))?.let {
            RestoreDestination.clearPark(app)
            RestoreStaging.discard(root)
            return Outcome.Refused(it)
        }

        // Step 3: blind the process before anything closes: an extension calling into its store meets the locked library.
        val oldPassphrase = KeySession.get()
        KeySession.clear()

        val live = Live(SoilFiles.indexFile(app), SoilFiles.gardenDir(app))
        val aside = File(root, ASIDE_DIR)
        val marks = Marks()
        return try {
            AppStores.closeAll(app)
            SoilIndex.closeForRotation(app)
            afterClose(app, root, live, aside, staging, manifest, proven, oldPassphrase, marks, leftOut, missing)
        } catch (e: Exception) {
            Log.e(TAG, "commit threw after the session was cleared (swap begun: ${marks.swapBegun})", e)
            val landed = marks.swapBegun && live.index.isFile
            runCatching { executeRecovery(root, live, aside, staging) }.onFailure { Log.e(TAG, "in-process recovery threw; the next launch finishes it", it) }
            val problem = Problem.Unexpected(e.javaClass.simpleName)
            if (landed) {
                Outcome.Interrupted(problem)
            } else {
                runCatching { RestoreDestination.clearPark(app) }
                oldPassphrase?.let { KeySession.set(it) }
                Outcome.RolledBack(problem)
            }
        }
    }

    private class Marks(var swapBegun: Boolean = false)

    private fun afterClose(app: Context, root: File, live: Live, aside: File, staging: File, manifest: RestoreManifest, proven: String, oldPassphrase: String?, marks: Marks, leftOut: List<String>, missing: List<String>): Outcome {
        marks.swapBegun = true
        val failedStep = swap(live, aside, staging)
        if (failedStep != null) {
            Log.e(TAG, "swap failed at step $failedStep; renaming the aside back")
            if (!executeRecovery(root, live, aside, staging)) Log.e(TAG, "the rename back did not finish; the next launch tries again")
            RestoreDestination.clearPark(app)
            oldPassphrase?.let { KeySession.set(it) }
            return Outcome.RolledBack(Problem.SwapFailed(failedStep))
        }

        // Key state: the installed index's key becomes this device's global, acknowledged (the person demonstrably has it).
        PassphraseStore.setGlobalPassphrase(app, proven)
        PassphraseStore.setRecoveryKeyAcknowledged(app)
        KeyMaterial.clearAll(app)
        KeySession.set(proven)
        PassphraseStore.clearRotationMarker(app)

        // Discard the aside (no undo) and whatever staging has left.
        if (!aside.deleteRecursively()) Log.w(TAG, "aside discard was incomplete; the next launch finishes it")
        RestoreStaging.discard(root)
        RealRekeyFs.fsyncDir(root)

        Slog.d(TAG) { "restore committed: ${manifest.itemCount} items, ${manifest.storeCount} stores" }
        return Outcome.Committed(manifest.itemCount, manifest.storeCount, leftOut, missing)
    }

    internal class Live(val index: File, val garden: File)

    /** Steps (a) to (e). The letter of the rename that failed, or null. */
    private fun swap(live: Live, aside: File, staging: File): Char? {
        val fs = RealRekeyFs
        if (aside.exists() && !aside.deleteRecursively()) return 'a'
        if (!aside.mkdirs()) return 'a'
        val root = live.index.parentFile ?: return 'a'

        // (a) live index + sidecars → aside; the index first, since it is the marker both ways.
        if (!fs.rename(live.index, File(aside, live.index.name))) return 'a'
        for (sidecar in RekeyNames.sidecarsOf(live.index)) {
            if (sidecar.exists() && !fs.rename(sidecar, File(aside, sidecar.name))) return 'a'
        }
        fs.fsyncDir(root)

        // (b) live garden → aside/garden.
        if (live.garden.exists() && !fs.rename(live.garden, File(aside, RestoreRecovery.GARDEN_NAME))) return 'b'
        fs.fsyncDir(root)

        // (c) staged garden → live.
        if (!fs.rename(File(staging, RestoreRecovery.GARDEN_NAME), live.garden)) return 'c'
        fs.fsyncDir(root)

        // (d) the staged index's WAL beside the live name; a -shm is deleted, never moved.
        val stagedIndex = File(staging, live.index.name)
        for (sidecar in RekeyNames.sidecarsOf(stagedIndex)) {
            if (!sidecar.exists()) continue
            if (sidecar.name.endsWith(SHM)) { sidecar.delete(); continue }
            if (!fs.rename(sidecar, File(root, sidecar.name))) return 'd'
        }
        // (e) the staged index → live, last: the commit marker.
        if (!fs.rename(stagedIndex, live.index)) return 'e'
        fs.fsyncDir(root)
        return null
    }

    // ── Interrupted-commit recovery ──────

    /** The launch-time repair, before the index is looked at. Idempotent, never throws. Blocking: called on IO. */
    fun recoverInterrupted(context: Context) {
        try {
            val app = context.applicationContext
            val root = SoilFiles.root(app)
            val aside = File(root, ASIDE_DIR)
            val staging = RestoreStaging.dir(root)
            if (!aside.exists() && !staging.exists()) return
            executeRecovery(root, Live(SoilFiles.indexFile(app), SoilFiles.gardenDir(app)), aside, staging)
        } catch (e: Exception) {
            Log.e(TAG, "interrupted-restore recovery failed", e)
        }
    }

    /** True when every planned action succeeded; it stops at the first that fails. */
    internal fun executeRecovery(root: File, live: Live, aside: File, staging: File): Boolean {
        val asideIndex = File(aside, live.index.name)
        val asideGarden = File(aside, RestoreRecovery.GARDEN_NAME)
        val state = RestoreRecovery.State(
            liveIndex = live.index.isFile,
            asideIndex = asideIndex.isFile,
            liveGarden = live.garden.isDirectory,
            asideGarden = asideGarden.isDirectory,
            asideSidecars = RekeyNames.sidecarsOf(asideIndex).filter { it.exists() }.map { it.name },
        )
        val actions = RestoreRecovery.plan(state)
        Log.w(TAG, "restore recovery: $state → $actions")
        // When the old index is about to come back, a sidecar at the live name is the new index's and would be replayed into the old file.
        if (!state.liveIndex && state.asideIndex) {
            for (sidecar in RekeyNames.sidecarsOf(live.index)) {
                if (sidecar.exists() && !sidecar.delete()) Log.e(TAG, "stray ${sidecar.name} could not be cleared")
            }
        }
        val whole = RestoreRecovery.run(actions) { action ->
            val ok = when (action) {
                RestoreRecovery.Action.DeleteAside -> !aside.exists() || aside.deleteRecursively()
                RestoreRecovery.Action.DeleteStaging -> !staging.exists() || staging.deleteRecursively()
                RestoreRecovery.Action.DeleteLiveGarden -> live.garden.deleteRecursively()
                is RestoreRecovery.Action.RenameBack -> {
                    val from = File(aside, action.name)
                    val to = File(root, action.name)
                    // A plain file squatting on a directory's name, or a directory on the index's, would block the rename back forever.
                    if (from.exists() && to.exists() && from.isDirectory != to.isDirectory) {
                        if (!to.deleteRecursively()) Log.e(TAG, "obstruction at ${to.name} could not be cleared")
                    }
                    !from.exists() || RealRekeyFs.rename(from, to)
                }
            }
            // Stop here: renaming the index back over a half-repaired garden would let the next launch read it as a finished commit and discard the old library.
            if (!ok) Log.e(TAG, "restore recovery action failed: $action; stopping, the next launch tries again")
            ok
        }
        RealRekeyFs.fsyncDir(root)
        if (aside.isDirectory && aside.list().isNullOrEmpty()) aside.delete()
        return whole
    }

    /**
     * The old library's index still stands aside: a recovery has not finished. The index must not
     * be created fresh while this holds, or the next launch reads the new file as a finished commit.
     */
    fun asideIndexStands(context: Context): Boolean =
        File(File(SoilFiles.root(context.applicationContext), ASIDE_DIR), SoilFiles.indexFile(context.applicationContext).name).isFile

    private const val TAG = "RestoreEngine"
}
