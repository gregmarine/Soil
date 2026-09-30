package com.symmetricalpalmtree.soil.crypto

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The in-place rekey of a file** — one recipe for an item file, a store and the index alike,
 * and the only thing in Soil allowed to change the key a file on disk is under.
 *
 *  1. **The file must be cold** — no connection in this process ([OpenFiles]) — and its WAL
 *     absorbed: an open under the *current* key, `wal_checkpoint(TRUNCATE)`, close, then the
 *     sidecar sweep. A WAL that will not absorb stops the rekey before anything is written;
 *     **a non-empty `-wal` is never deleted**.
 *  2. [RekeyExport.exportAndKeyToPrimary] from the file into `X.rekey.tmp` under the new key —
 *     `user_version` carried, acceptance = probe + open + `integrity_check` + version. A failure
 *     deletes only the tmp.
 *  3. [RekeyCommit.commitReplace] — fsync'd, the original never at risk.
 *  4. [KeyMaterial.invalidate] — the salt changed, so the cached raw key is stale.
 *
 * `PRAGMA rekey` is never used. Passphrases reach this object as parameters and leave it only as
 * SQL literals on a local connection; none is logged.
 *
 * [recoverGarden] is the other half: it runs once the index is open, and rotation's resume runs
 * it before its loop, so a death anywhere inside step 3 is put right on the next launch by
 * [RekeyRecovery]'s decision table. The index has no directory listing of its own —
 * `SoilIndex.ensureReady` calls [recoverOne] for it before it would ever treat a missing file as
 * a fresh install.
 */
object SoilRekey {

    private const val TAG = "SoilRekey"

    /**
     * Re-key [file] (cache id [fileId]) from [oldPassphrase] to [newPassphrase] in place. IO.
     * Throws `IllegalStateException` with a path-free message on any failure, and in every
     * failure the original is exactly as it was.
     *
     * [oldRawKey], when given, is the **verified** raw key of [file] under [oldPassphrase] — it
     * saves the two KDFs the source side would otherwise pay, once to absorb the WAL and once to
     * attach. It is only ever an optimisation: null takes the passphrase road. The new key is
     * always derived from [newPassphrase] — a raw key is bound to the file it came from.
     */
    suspend fun rekeyInPlace(
        context: Context,
        file: File,
        fileId: String,
        oldPassphrase: String,
        newPassphrase: String,
        oldRawKey: ByteArray? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        SoilCrypto.requireExisting(file)
        if (OpenFiles.isOpen(file)) throw IllegalStateException("the file is open in this process")

        absorbWal(file, oldPassphrase, oldRawKey)

        val tmp = RekeyNames.tmpFor(file)
        RekeyExport.rejectOutput(tmp)
        RekeyExport.exportAndKeyToPrimary(
            out = tmp,
            sourcePath = file.path,
            attachKeyLiteral = attachLiteral(oldPassphrase, oldRawKey),
            destPassphrase = newPassphrase,
            what = "re-keyed",
        )

        when (val outcome = RekeyCommit.commitReplace(RealRekeyFs, file, tmp)) {
            RekeyCommit.Outcome.Committed -> Unit
            RekeyCommit.Outcome.BothKept -> {
                // The original is `.old.bak`, the new file is `.rekey.tmp`, neither deleted —
                // recovery renames whichever verifies back in.
                Log.w(TAG, "commit left both copies for $fileId; recovery will finish it")
                throw IllegalStateException("the re-keyed file could not be moved into place")
            }
            else -> {
                RekeyExport.rejectOutput(tmp)
                Log.w(TAG, "commit did not happen for $fileId: ${outcome.javaClass.simpleName}")
                throw IllegalStateException("the re-keyed file could not be moved into place")
            }
        }

        KeyMaterial.invalidate(app, fileId)
        Slog.d(TAG) { "re-keyed $fileId (${file.length()} bytes)" }
    }

    /**
     * How the source side of the transform spells its key: the verified raw key in SQLCipher's
     * `x'…'` raw-key spelling when the caller had one, else the passphrase — **both as a SQL
     * string literal** with `''` doubling. Pure, and the one place the choice is made.
     *
     * The raw key must travel as **text** (`'x''…'''`), never as a bare `x'…'` blob literal. An
     * `ATTACH … KEY` evaluates its key as a SQL expression, and SQLCipher recognises the raw-key
     * form only on a TEXT value that starts with `x'`; a BLOB value is taken as a passphrase and
     * put through the KDF, which reads as a wrong key with the right key in hand. The open-time
     * key ([SoilCrypto.openRawKey]) arrives as text already, so needs no quoting.
     */
    internal fun attachLiteral(passphrase: String, rawKey: ByteArray?): String =
        RekeyExport.sqlLiteral(if (rawKey != null) RawKeyDerivation.rawKeyLiteral(rawKey) else passphrase)

    /**
     * Fold the WAL into the main file under the current key and sweep the empty sidecars. Throws
     * when a non-empty WAL is left afterwards: the commit would refuse it anyway, and stopping
     * here writes nothing.
     */
    private fun absorbWal(file: File, passphrase: String, rawKey: ByteArray?) {
        val db = try {
            if (rawKey != null) SoilCrypto.openRawKey(file, rawKey) else SoilCrypto.openRaw(file, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "absorb open failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the file could not be opened with its current key")
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null as Array<String>?).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint failed: ${e.javaClass.simpleName}")
        } finally {
            runCatching { db.close() }
        }
        Sidecars.sweep(file)
        val wal = File(file.path + "-wal")
        if (wal.exists() && wal.length() > 0L) throw IllegalStateException("the file still has unabsorbed writes")
    }

    // ── Recovery ─────────────────────────────────────────────────────────────

    /**
     * Put right every interrupted commit in the garden (after the index is open; rotation resume,
     * before its loop). [verifies] is the trusted-key test. Never throws; returns how many
     * originals had leftovers. IO.
     */
    suspend fun recoverGarden(context: Context, verifies: (File) -> Boolean): Int = withContext(Dispatchers.IO) {
        // The directory is read by `SoilFiles`, the one path authority.
        val originals = SoilFiles.rekeyLeftovers(context.applicationContext)
        for (original in originals) {
            val result = try {
                RekeyRecovery.recover(RealRekeyFs, original, verifies)
            } catch (e: Exception) {
                Log.w(TAG, "recovery threw for a garden file: ${e.javaClass.simpleName}")
                RekeyRecovery.Result.FAILED
            }
            Log.w(TAG, "recovered ${original.name.substringAfterLast('.')} leftovers: $result")
        }
        originals.size
    }

    /** [RekeyRecovery.recover] for one named file outside the garden listing — the index. */
    fun recoverOne(original: File, verifies: (File) -> Boolean): RekeyRecovery.Result =
        try {
            RekeyRecovery.recover(RealRekeyFs, original, verifies)
        } catch (e: Exception) {
            Log.w(TAG, "recovery threw for ${original.name}: ${e.javaClass.simpleName}")
            RekeyRecovery.Result.FAILED
        }

    /** True iff a `.rekey.tmp` or `.old.bak` stands beside [original]. */
    fun hasLeftovers(original: File): Boolean =
        RekeyNames.tmpFor(original).exists() || RekeyNames.bakFor(original).exists()
}
