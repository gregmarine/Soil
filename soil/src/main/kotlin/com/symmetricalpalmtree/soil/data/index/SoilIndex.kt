package com.symmetricalpalmtree.soil.data.index

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.crypto.GlobalKey
import com.symmetricalpalmtree.soil.crypto.GlobalRotation
import com.symmetricalpalmtree.soil.crypto.KeyMaterial
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.OpenFiles
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.crypto.SoilRekey
import com.symmetricalpalmtree.soil.data.FileKey
import com.symmetricalpalmtree.soil.data.SoilDb
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * Owns the library index (`soil.db`) — encrypted under the global key from the first byte, opened
 * through the derive-once raw-key cache so a cold launch stays fast.
 *
 * **The index being open is what "the library is unlocked" means.** Soil is the home screen, so
 * no screen can forward to a bootstrap and finish: the state lives here, in the process, as
 * [state], and every screen renders or routes by it.
 *
 * Open state machine (probe the file header, never open to find out):
 *  - `Invalid` + no file (or zero bytes) → mint (or reuse) the global key → create encrypted →
 *    derive + cache the raw key → [State.READY]. `Invalid` over an existing non-empty file is a
 *    damaged index — [State.DAMAGED_FILE], never created over, never deleted.
 *  - `Encrypted` → cached passphrase? → raw key **verified** against the file → open → READY.
 *    No cached passphrase, or the cached one no longer fits → [State.NEEDS_UNLOCK].
 *  - `Plaintext` → impossible for Soil (there is no plaintext mode). Never opened: it is a
 *    foreign file. [State.FOREIGN_FILE].
 */
object SoilIndex {

    enum class State {
        /** Not looked at yet, or being opened. The first launch spends seconds here, deriving keys. */
        PREPARING,
        READY,
        /** The file is there and no key on this device opens it. */
        NEEDS_UNLOCK,
        /** A plaintext database where the index should be. Not Soil's; never opened. */
        FOREIGN_FILE,
        /** Something unreadable where the index should be. Never created over, never deleted. */
        DAMAGED_FILE,
        /** The device's storage could not be reached, or the open failed for a reason that is not
         *  the key. Nothing was changed. */
        UNAVAILABLE,
    }

    private const val TAG = "SoilIndex"

    @Volatile
    private var instance: ZeticDB? = null

    private val prepareMutex = Mutex()

    private val _state = MutableStateFlow(State.PREPARING)
    val state: StateFlow<State> get() = _state

    fun isReady(): Boolean = instance != null

    fun db(): ZeticDB =
        instance ?: throw IllegalStateException("the index is not open")

    /** Bring the index to an open state and publish what was found. Idempotent; safe to call
     *  concurrently. IO. */
    suspend fun ensureReady(context: Context): State = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withLock State.READY
            val app = context.applicationContext
            val found = try {
                prepare(app)
            } catch (e: Exception) {
                // Never the message: an open's message can carry a path.
                Log.w(TAG, "the index could not be opened: ${e.javaClass.simpleName}")
                State.UNAVAILABLE
            }
            if (found == State.READY) {
                // A re-key commit that died between its two renames is put right here, before
                // anything opens a garden file.
                runCatching { SoilRekey.recoverGarden(app, GlobalRotation.trustedVerifier(app)) }
            }
            found
        }.also { _state.value = it }
    }

    private fun prepare(app: Context): State {
        val file = SoilFiles.indexFile(app)

        // An index missing because a rekey commit died between its two renames is NOT a fresh
        // install — its bytes are `soil.db.rekey.tmp` / `.old.bak` beside it. Recover with a
        // trusted key before the probe can ever answer "create". With no cached passphrase nothing
        // can be verified and nothing is touched: DAMAGED_FILE, and both files stay for a person
        // to look at.
        if ((!file.exists() || file.length() == 0L) && SoilRekey.hasLeftovers(file)) {
            if (PassphraseStore.getGlobalPassphrase(app) == null && PassphraseStore.getRotationMarker(app) == null) {
                return State.DAMAGED_FILE
            }
            val result = SoilRekey.recoverOne(file, GlobalRotation.trustedVerifier(app))
            Log.w(TAG, "index rekey leftovers: $result")
            if (!file.exists() || file.length() == 0L) return State.DAMAGED_FILE
        }

        return when (SoilCrypto.probe(file)) {
            SoilFileKind.Invalid -> {
                // `Invalid` covers missing/empty AND unreadable/truncated. Only a genuinely absent
                // (or zero-byte) file is a fresh install; an existing remnant must never be built
                // over: a create here would initialize a brand-new empty index on top of it.
                if (file.exists() && file.length() > 0L) return State.DAMAGED_FILE
                // An index that is gone while a key is still cached is a library that was removed
                // from under Soil. The key is reused, so whatever is left in the garden opens.
                val pass = GlobalKey.ensure(app)
                val db = SoilDb.create(file, pass, IndexSchema.SCHEMA) // the one native KDF
                finishOpen(app, file, db, pass)
                // The file now has a salt — cache its raw key so later launches skip the KDF.
                runCatching { KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass) }
                    .onFailure { Log.w(TAG, "raw-key warm failed after create: ${it.javaClass.simpleName}") }
                Slog.d(TAG) { "index created" }
                State.READY
            }

            SoilFileKind.Encrypted -> {
                val pass = PassphraseStore.getGlobalPassphrase(app)
                    ?: return openUnderMarkerOrUnlock(app, file)
                var key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass)
                if (!SoilCrypto.verifyRawKey(file, key)) {
                    // The cached material no longer opens this file (restored from elsewhere,
                    // etc.). Drop the derived key; if the passphrase itself is right, re-derive.
                    KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
                    if (!SoilCrypto.verifyPassphrase(file, pass)) return openUnderMarkerOrUnlock(app, file)
                    key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass)
                }
                finishOpen(app, file, SoilDb.open(file, FileKey.Raw(key), IndexSchema.SCHEMA), pass)
                State.READY
            }

            SoilFileKind.Plaintext -> State.FOREIGN_FILE
        }
    }

    /**
     * Unlock with a passphrase a person typed (the NEEDS_UNLOCK path). Verifies against the file
     * first — never opens with an unverified key — caches it as the global passphrase, opens.
     * False on a wrong passphrase; the file is untouched either way. IO.
     */
    suspend fun unlockAndOpen(context: Context, passphrase: String): Boolean = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withLock true
            val app = context.applicationContext
            val file = SoilFiles.indexFile(app)
            if (!SoilCrypto.verifyPassphrase(file, passphrase)) return@withLock false
            PassphraseStore.setGlobalPassphrase(app, passphrase)
            KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
            val key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, passphrase)
            finishOpen(app, file, SoilDb.open(file, FileKey.Raw(key), IndexSchema.SCHEMA), passphrase)
            _state.value = State.READY
            true
        }
    }

    /**
     * The one door that closes the index for a **rotation**: checkpoint, close, forget the
     * instance. The rotation must re-key the file, and cannot while a connection holds it. The
     * caller reopens with [ensureReady] when it is done. Idempotent; never throws. IO.
     */
    suspend fun closeForRotation(context: Context) = withContext(Dispatchers.IO) {
        prepareMutex.withLock { close(context.applicationContext, State.PREPARING) }
    }

    /**
     * **Forget on this device**: close the index and every store and drop the session's key. The
     * files are untouched; the recovery key opens them again. The caller clears what is stored.
     * Idempotent; never throws. IO.
     */
    suspend fun lock(context: Context) = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val app = context.applicationContext
            AppStores.closeAll(app)
            close(app, State.NEEDS_UNLOCK)
            KeySession.clear()
        }
    }

    private fun close(app: Context, then: State) {
        val db = instance
        if (db != null) {
            SoilDb.checkpoint(db)
            runCatching { db.close() }.onFailure { Log.w(TAG, "close failed: ${it.javaClass.simpleName}") }
            runCatching { OpenFiles.release(SoilFiles.indexFile(app)) }
            instance = null
            Slog.d(TAG) { "index closed" }
        }
        _state.value = then
    }

    /**
     * Resume path 3. The cached global (if any) does not open the index. A rotation that died
     * **after the index's own rekey and before its commit** leaves exactly this: the index under
     * the marker's new passphrase, the cache still holding the old one. Try the marker's key; if
     * it opens, finish the rotation here and answer READY. No marker, or a marker whose key does
     * not fit either → NEEDS_UNLOCK, and nothing is touched. Runs under the prepare mutex.
     */
    private fun openUnderMarkerOrUnlock(app: Context, file: File): State {
        val marker = PassphraseStore.getRotationMarker(app) ?: return State.NEEDS_UNLOCK
        val pass = marker.newPassphrase
        if (!SoilCrypto.verifyPassphrase(file, pass)) return State.NEEDS_UNLOCK
        Log.w(TAG, "index opens under the rotation marker's key; committing the rotation")
        KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
        val key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass)
        // Commits the rotation: the marker's key is now the session's.
        finishOpen(app, file, SoilDb.open(file, FileKey.Raw(key), IndexSchema.SCHEMA), pass)
        // The commit clears every raw key (all derived against old salts) — re-warm the index's
        // own afterwards so the next launch is a raw-key open again.
        runCatching { KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass) }
            .onFailure { Log.w(TAG, "raw-key warm failed after rotation commit: ${it.javaClass.simpleName}") }
        return State.READY
    }

    private fun finishOpen(app: Context, file: File, db: ZeticDB, passphrase: String) {
        instance = db
        OpenFiles.claim(file)
        KeySession.set(passphrase)
        // A rotation that died between `setGlobalPassphrase(new)` and clearing its marker: the
        // cached global IS the marker's key, so everything is done — finish the commit here rather
        // than send the person through a Resume that would only skip every file.
        val marker = PassphraseStore.getRotationMarker(app)
        if (marker != null && marker.newPassphrase == passphrase) GlobalRotation.commit(app, marker)
    }
}
