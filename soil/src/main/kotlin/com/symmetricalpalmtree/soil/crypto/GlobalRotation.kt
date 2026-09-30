package com.symmetricalpalmtree.soil.crypto

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * **The global rotation**: re-key every file the device's global key opens — the items under the
 * global key, then every store, then the index **last** — from the cached global passphrase to a
 * new one, journaled so that a death anywhere is resumable and nothing is ever in a state only a
 * lost key opens.
 *
 * **Journal first.** [start] writes the [RotationMarker] to [PassphraseStore] before any file is
 * touched and rewrites it after every file. The cached global stays the OLD passphrase until
 * [commit]: between start and commit the library is in two keys, and three resume paths cover a
 * death in that window — the Encryption screen's banner ([resume]), every key-gated screen
 * leading there while a marker exists, and `SoilIndex.ensureReady` trying the marker's new
 * passphrase for an index that no longer opens under the old one and committing itself.
 *
 * **Per file** ([RotationPlan.beforeRekey] → [RotationPlan.decide]): on a `start` the cached raw
 * key is tried first — a raw key that still opens the file says "under the old key" for free;
 * otherwise one KDF verify answers. On a `resume` the new key is verified **first**, because a
 * cached raw key may have been warmed under either passphrase since. A file under the old key
 * goes through [SoilRekey.rekeyInPlace], the only thing in Soil that changes the key a file on
 * disk is under. An item under **neither** key is quarantined: marked as having a passphrase of
 * its own, dropped from pending, the rotation carries on, and the count is reported at the end. A
 * store or the index under neither stops the rotation with [Result.Failed] — nothing to
 * quarantine, nothing deleted. A rekey that throws is re-read ([RotationPlan.afterThrow]).
 *
 * **The rituals**: `AppStores.closeAll()` before the first store, and
 * `SoilIndex.closeForRotation()` before the index. After that this process touches no index row
 * until the caller reopens it.
 *
 * **When backup arrives**, its stamps must be cleared before the index is closed: a rekey leaves
 * `updatedAt` untouched, so a forgotten stamp would keep an old-key copy in every backup.
 *
 * **Cancel** ([AtomicBoolean]) is honoured between files; the current file always finishes. Each
 * file runs under [NonCancellable] so a dying activity scope can only land between files too.
 * Items with a passphrase of their own are never in the list. No passphrase is logged, ever.
 */
object GlobalRotation {

    private const val TAG = "GlobalRotation"

    /** What the progress dialog names for the file in hand. Item names are user content —
     *  they reach the dialog and nothing else. */
    sealed class Label {
        data class Item(val name: String) : Label()
        object Stores : Label()
        object Index : Label()
    }

    /** [done] of [total] finished; [label] is the one about to be re-keyed. */
    data class Progress(val done: Int, val total: Int, val label: Label)

    sealed class Result {
        /** Every file is under the new key and the commit is done. [items] = re-keyed (or
         *  already-done) items over the whole rotation; [quarantined] over the whole rotation. */
        data class Complete(val items: Int, val quarantined: Int) : Result()
        /** Stopped between files on the person's Cancel; the marker keeps [remaining]. */
        data class Cancelled(val remaining: Int, val quarantined: Int) : Result()
        data class Failed(val reason: Reason, val remaining: Int, val quarantined: Int) : Result()
    }

    enum class Reason {
        /** The cached global passphrase is gone (Forget mid-rotation, a Keystore wipe) — nothing
         *  can be opened under the old key. The marker stays; Unlock puts the global back. */
        NO_CACHED_GLOBAL,
        /** A file still under the old key could not be re-keyed (disk, a WAL that would not
         *  absorb). Kept pending — Resume tries it again. */
        TRANSIENT,
        /** A store or the index opens under neither key. Kept pending; nothing deleted. */
        STUCK,
    }

    fun hasMarker(context: Context): Boolean = PassphraseStore.getRotationMarker(context) != null

    /**
     * The trusted-key test for `SoilRekey.recoverGarden` while a rotation may be in flight: the
     * cached global **or** the marker's new passphrase. A `.rekey.tmp` the rotation wrote verifies
     * only under the new key, and a verifier that knew only the old one would roll a finished
     * rekey back.
     */
    fun trustedVerifier(context: Context): (File) -> Boolean {
        val app = context.applicationContext
        val global = PassphraseStore.getGlobalPassphrase(app)
        val marker = PassphraseStore.getRotationMarker(app)
        return { file ->
            (global != null && SoilCrypto.verifyPassphrase(file, global)) ||
                (marker != null && marker.newPassphrase != global && SoilCrypto.verifyPassphrase(file, marker.newPassphrase))
        }
    }


    /**
     * Begin a rotation to [newPassphrase]. The index must be open (the work list and the names come
     * from it). Writes the marker, then runs. IO.
     */
    suspend fun start(
        context: Context,
        newPassphrase: String,
        minted: Boolean,
        onProgress: suspend (Progress) -> Unit,
        cancel: AtomicBoolean,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val itemIds = IndexStore().globalItems().map { it.id }
        val stores = SoilFiles.storeNames(app)
        val ids = RotationPlan.order(itemIds, stores)
        val marker = RotationMarker(
            pendingIds = ids,
            newPassphrase = newPassphrase,
            minted = minted,
            total = ids.size,
            notebookCount = itemIds.size,
            startedAt = System.currentTimeMillis(),
        )
        PassphraseStore.setRotationMarker(app, marker)
        Slog.d(TAG) { "rotation started: ${itemIds.size} items, ${stores.size} stores, index" }
        run(app, marker, onProgress, cancel, resumed = false)
    }

    /** Continue the rotation the marker describes (the banner's Resume). IO. */
    suspend fun resume(
        context: Context,
        onProgress: suspend (Progress) -> Unit,
        cancel: AtomicBoolean,
    ): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val marker = PassphraseStore.getRotationMarker(app)
            ?: return@withContext Result.Complete(0, 0) // nothing to resume — already committed
        // A commit that died between its two renames: put the survivor in place under whichever
        // key it verifies with, before the loop can find an original missing.
        SoilRekey.recoverGarden(app, trustedVerifier(app))
        run(app, marker, onProgress, cancel, resumed = true)
    }

    private suspend fun run(
        app: Context,
        initial: RotationMarker,
        onProgress: suspend (Progress) -> Unit,
        cancel: AtomicBoolean,
        resumed: Boolean,
    ): Result {
        val old = PassphraseStore.getGlobalPassphrase(app)
            ?: return Result.Failed(Reason.NO_CACHED_GLOBAL, initial.pendingIds.size, initial.quarantined.size)
        val new = initial.newPassphrase
        // The index may have been closed by the run that died; the work list and the names come
        // from it, so it is opened again first. It opens under whichever key it is now under.
        if (!SoilIndex.isReady()) SoilIndex.ensureReady(app)
        if (!SoilIndex.isReady()) return Result.Failed(Reason.STUCK, initial.pendingIds.size, initial.quarantined.size)
        // A rotation that finished the index and died before its commit is committed by that open.
        if (PassphraseStore.getRotationMarker(app) == null) {
            return Result.Complete(initial.notebookCount - initial.quarantined.size, initial.quarantined.size)
        }
        val index = IndexStore()
        // The library is reachable between a Cancel and a Resume, so the list is re-read: an item
        // created since, and any store minted since, were made under the OLD key and must not be
        // left behind.
        val globals = index.globalItems()
        val extra = RotationPlan.resumeCandidates(
            globalNotebooks = globals.map { it.id to maxOf(it.createdAt, it.updatedAt) },
            pendingIds = initial.pendingIds.toSet(),
            startedAt = initial.startedAt,
            rawKeyOpens = { id -> KeyMaterial.peekVerified(app, id, SoilFiles.itemFile(app, id)) != null },
        )
        val stores = SoilFiles.storeNames(app)
        var marker = initial.augmented(extra, stores)
        if (marker != initial) {
            Log.w(TAG, "resume found ${extra.size} item(s) and ${stores.size} store(s) to check beyond the marker")
            PassphraseStore.setRotationMarker(app, marker)
        }
        // Names for the dialog, read while the index is open. Never stored anywhere.
        val names = globals.associate { it.id to it.name }
        var storesClosed = false
        // An item an app holds open would sit under its own rekey. The screen refuses to start
        // while one is; this covers the rest, and leaves every session parked.
        ItemSessions.releaseAll(app)

        for (id in marker.pendingIds) {
            coroutineContext.ensureActive()
            if (cancel.get()) return Result.Cancelled(marker.pendingIds.size, marker.quarantined.size)
            val kind = RotationPlan.kindOf(id)
            onProgress(Progress(marker.completed, marker.total, labelFor(kind, id, names)))

            val outcome = withContext(NonCancellable) {
                when (kind) {
                    RotationPlan.Kind.ITEM -> rotateItem(app, id, old, new, index, resumed)
                    RotationPlan.Kind.STORE -> {
                        if (!storesClosed) { AppStores.closeAll(app); storesClosed = true }
                        rotateFile(app, SoilFiles.storeFile(app, RotationPlan.storePackage(id)!!), id, kind, old, new, resumed)
                    }
                    RotationPlan.Kind.INDEX -> {
                        // A store opened since the loop began would hold the old key's connection.
                        AppStores.closeAll(app)
                        SoilIndex.closeForRotation(app)
                        rotateFile(app, SoilFiles.indexFile(app), id, kind, old, new, resumed)
                    }
                }
            }
            marker = when (outcome) {
                FileOutcome.DONE -> marker.without(id)
                FileOutcome.QUARANTINED -> marker.quarantine(id)
                FileOutcome.TRANSIENT -> return Result.Failed(Reason.TRANSIENT, marker.pendingIds.size, marker.quarantined.size)
                FileOutcome.STUCK -> return Result.Failed(Reason.STUCK, marker.pendingIds.size, marker.quarantined.size)
            }
            PassphraseStore.setRotationMarker(app, marker)
        }

        commit(app, marker)
        return Result.Complete(marker.notebookCount - marker.quarantined.size, marker.quarantined.size)
    }

    private fun labelFor(kind: RotationPlan.Kind, id: String, names: Map<String, String>): Label = when (kind) {
        RotationPlan.Kind.ITEM -> Label.Item(names[id] ?: "")
        RotationPlan.Kind.STORE -> Label.Stores
        RotationPlan.Kind.INDEX -> Label.Index
    }

    private enum class FileOutcome { DONE, QUARANTINED, TRANSIENT, STUCK }

    private suspend fun rotateItem(
        app: Context, id: String, old: String, new: String, index: IndexStore, resumed: Boolean,
    ): FileOutcome {
        val file = SoilFiles.itemFile(app, id)
        if (!file.exists() || file.length() == 0L) {
            // An alive row with no file: nothing to re-key, nothing this rotation can put right.
            Log.w(TAG, "item file missing; skipped")
            return FileOutcome.DONE
        }
        val outcome = rotateFile(app, file, id, RotationPlan.Kind.ITEM, old, new, resumed)
        if (outcome == FileOutcome.QUARANTINED) {
            index.quarantine(id)
            Log.w(TAG, "item quarantined (opens under neither key)")
        }
        return outcome
    }

    /** One file through [RotationPlan.beforeRekey] / [RotationPlan.afterThrow]. */
    private suspend fun rotateFile(
        app: Context, file: File, fileId: String, kind: RotationPlan.Kind, old: String, new: String,
        resumed: Boolean,
    ): FileOutcome {
        // Kept when the plan actually asked: where `beforeRekey` reads a raw-key hit it reads it
        // AS the old key, so the rekey can attach and checkpoint with it instead of deriving the
        // same key twice more. Null everywhere the question was not asked.
        var oldRawKey: ByteArray? = null
        val step = RotationPlan.beforeRekey(
            kind,
            resumed = resumed,
            // Stale ones are dropped there. A hit is "under the old key" only where
            // beforeRekey asks for it (a start, or a resume once the new key has failed).
            rawKeyOpens = { KeyMaterial.peekVerified(app, fileId, file)?.also { oldRawKey = it } != null },
            opensUnderNew = { SoilCrypto.verifyPassphrase(file, new) },
            opensUnderOld = { SoilCrypto.verifyPassphrase(file, old) },
        )
        return when (step) {
            RotationPlan.Step.SKIP -> {
                KeyMaterial.invalidate(app, fileId) // derived against the old salt, if at all
                FileOutcome.DONE
            }
            RotationPlan.Step.REKEY -> try {
                SoilRekey.rekeyInPlace(app, file, fileId, old, new, oldRawKey)
                FileOutcome.DONE
            } catch (e: Exception) {
                Log.w(TAG, "rekey failed: ${e.message}")
                // A commit that left `X.old.bak` + `X.rekey.tmp` and no `X` is finished here —
                // the tmp verifies under the new key, so recovery renames it back — before
                // anything reads the key question off a file that is not there.
                when (RotationPlan.afterThrow(
                    kind,
                    originalExists = { file.exists() },
                    recover = {
                        val result = SoilRekey.recoverOne(file) { f ->
                            SoilCrypto.verifyPassphrase(f, new) || SoilCrypto.verifyPassphrase(f, old)
                        }
                        Log.w(TAG, "original missing after the rekey threw; recovery: $result")
                    },
                    opensUnderNew = { SoilCrypto.verifyPassphrase(file, new) }, // the commit landed late, or recovery finished it
                    opensUnderOld = { SoilCrypto.verifyPassphrase(file, old) },
                )) {
                    RotationPlan.Aftermath.DONE -> { KeyMaterial.invalidate(app, fileId); FileOutcome.DONE }
                    RotationPlan.Aftermath.TRANSIENT -> FileOutcome.TRANSIENT
                    RotationPlan.Aftermath.QUARANTINE -> FileOutcome.QUARANTINED
                    RotationPlan.Aftermath.STOP -> FileOutcome.STUCK
                }
            }
            RotationPlan.Step.QUARANTINE -> FileOutcome.QUARANTINED
            RotationPlan.Step.STOP -> FileOutcome.STUCK
        }
    }

    /**
     * The commit — [RotationPlan.commitSteps] executed in order. Also the tail of resume path 3:
     * `SoilIndex.ensureReady` calls this once the index has opened under the marker's new
     * passphrase. Idempotent. Nothing here touches the index.
     */
    fun commit(context: Context, marker: RotationMarker) {
        val app = context.applicationContext
        for (step in RotationPlan.commitSteps(marker.minted)) {
            when (step) {
                RotationPlan.CommitStep.SET_GLOBAL -> PassphraseStore.setGlobalPassphrase(app, marker.newPassphrase)
                RotationPlan.CommitStep.CLEAR_ACK -> PassphraseStore.clearRecoveryKeyAcknowledged(app)
                RotationPlan.CommitStep.CLEAR_RAW_KEYS -> KeyMaterial.clearAll(app)
                RotationPlan.CommitStep.SET_SESSION -> KeySession.set(marker.newPassphrase)
                RotationPlan.CommitStep.CLEAR_MARKER -> PassphraseStore.clearRotationMarker(app)
            }
        }
        Slog.d(TAG) { "rotation committed (minted=${marker.minted}, quarantined=${marker.quarantined.size})" }
    }
}
