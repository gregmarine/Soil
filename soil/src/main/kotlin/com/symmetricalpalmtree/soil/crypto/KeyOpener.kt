package com.symmetricalpalmtree.soil.crypto

import android.content.Context
import com.symmetricalpalmtree.soil.data.FileKey
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chooses the fastest correct key for an **existing** encrypted file, backed by the raw-key
 * cache ([KeyMaterial]).
 *
 * Cache hit: the raw key is verified against the file first (a cheap open). A stale key (the file
 * behind this id was swapped, so its salt changed) is invalidated instead of locking the person
 * out, and the passphrase is used. Cache miss: the passphrase now (native KDF on this one
 * connection) while the raw key is derived in the background so the next open is fast.
 *
 * Blocking (verify): call on Dispatchers.IO. Key material is never logged.
 */
object KeyOpener {

    private const val TAG = "KeyOpener"

    /** One derive at a time: a run that opens many cold files in a row must queue its warms, not
     *  race them — each is a full KDF, and a burst of concurrent ones exhausted native memory on
     *  the Nomad. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** The key to open the existing [file] with. Throws [SoilLockedException] if the file is
     *  missing or empty — this path never creates. */
    fun keyFor(context: Context, fileId: String, file: File, passphrase: String): FileKey {
        SoilCrypto.requireExisting(file)
        val cached = KeyMaterial.peekVerified(context, fileId, file)
        if (cached != null) {
            Slog.d(TAG) { "raw-key open: $fileId" }
            return FileKey.Raw(cached)
        }
        warm(context, fileId, file, passphrase)
        Slog.d(TAG) { "passphrase open (cold; warming raw key): $fileId" }
        return FileKey.Passphrase(passphrase)
    }

    /** Derive + cache [file]'s raw key in the background. No-op if cached. Never throws. */
    fun warm(context: Context, fileId: String, file: File, passphrase: String) {
        val app = context.applicationContext
        // The generation at queue time: a rekey that lands while this waits its turn (the queue is
        // serial and a derive is ~9 s) must not have its invalidate undone by a late store.
        val gen = KeyMaterial.generation(fileId)
        warmScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { KeyMaterial.rawKey(app, fileId, file, passphrase, ifGeneration = gen) }
                .onSuccess {
                    val kept = KeyMaterial.generation(fileId) == gen
                    Slog.d(TAG) { "warmed $fileId in ${android.os.SystemClock.elapsedRealtime() - t0} ms${if (kept) "" else " — discarded, the key was dropped meanwhile"}" }
                }
                .onFailure { Slog.d(TAG) { "warm failed for $fileId: ${it.message}" } }
        }
    }
}
