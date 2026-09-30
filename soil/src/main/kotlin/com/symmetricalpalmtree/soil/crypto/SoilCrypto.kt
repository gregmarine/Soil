package com.symmetricalpalmtree.soil.crypto

import android.util.Log
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/** What a probe of a database file found. Soil has no plaintext mode. */
enum class SoilFileKind { Plaintext, Encrypted, Invalid }

/**
 * Single canonical crypto-aware open — **the one door**.
 *
 * Every SQLCipher open in Soil (the index, a store, an item file) routes through here. Never open
 * a zetetic [ZeticDB] anywhere else.
 *
 * Rules that keep the data-loss bug family out:
 *  - **Every open passes [KeepFileHandler].** Left to its default, SQLCipher's error handler may
 *    delete a file it deems corrupt, and a wrong key reads as corruption. A null handler is never
 *    passed, anywhere.
 *  - Every *open* requires the file to exist and be non-empty ([requireExisting]). The underlying
 *    opens are create-capable; pointed at a missing path they would fabricate an empty database
 *    that masquerades as the real one. Creation has its own explicitly named entry point
 *    ([createRaw]), which refuses an existing file.
 *  - **A missing key is never "plaintext".** There is no keyless open.
 *  - Key encoding: the passphrase as it is typed, handed to SQLCipher as text. Stock SQLCipher 4
 *    defaults (default KDF, default page size — never customise `kdf_iter` / `cipher_page_size`),
 *    so a file opens with the same passphrase on any stock SQLCipher 4 build.
 */
object SoilCrypto {

    private const val TAG = "SoilCrypto"

    /**
     * SQLCipher's native half, loaded once, by the first open. Nothing loads it for us: there is
     * no open helper in Soil. Lazy rather than in an `init`, so the pure parts of this object
     * ([keyBytes], [probe]) run on the JVM, where the library does not exist.
     */
    private val native: Unit by lazy { System.loadLibrary("sqlcipher") }

    /** Canonical passphrase to key-bytes encoding. Must be UTF-8; do not change. */
    fun keyBytes(passphrase: String): ByteArray = passphrase.toByteArray(Charsets.UTF_8)

    // ── Opens — exists-guarded ───────────────────────────────────────────────

    /** Encrypted open with the passphrase (native KDF). [file] must exist and be non-empty. */
    fun openRaw(file: File, passphrase: String): ZeticDB {
        requireExisting(file)
        native
        return ZeticDB.openOrCreateDatabase(file, passphrase, null, KeepFileHandler)
    }

    /** Encrypted open with a raw key (KDF skipped). [file] must exist and be non-empty. */
    fun openRawKey(file: File, rawKey: ByteArray): ZeticDB {
        requireExisting(file)
        native
        return ZeticDB.openOrCreateDatabase(file, RawKeyDerivation.rawKeyLiteral(rawKey), null, KeepFileHandler)
    }

    /**
     * Encrypted open with the passphrase, **read-only**. [file] must exist and be non-empty.
     *
     * The one open that leaves the bytes on disk exactly as they were: a read-only connection
     * never checkpoints, so a `-wal` sidecar beside [file] is read through but neither replayed
     * into the main file nor unlinked when the connection closes. It can still create a `-shm`
     * beside the file when none exists, so the directory must be writable.
     */
    fun openRawReadOnly(file: File, passphrase: String): ZeticDB {
        requireExisting(file)
        native
        return ZeticDB.openDatabase(
            file.path, passphrase, null,
            ZeticDB.OPEN_READONLY or ZeticDB.NO_LOCALIZED_COLLATORS, KeepFileHandler, null,
        )
    }

    /** True iff [passphrase] opens [file]. False for a missing/empty file (a create-capable open
     *  would otherwise mint an empty DB keyed to whatever was typed and "verify" against nothing).
     *  **Opens read-write**: on a WAL-mode file the close checkpoints and unlinks a `-wal`
     *  sidecar — use [verifyPassphraseReadOnly] where the bytes on disk must stay what they are. */
    fun verifyPassphrase(file: File, passphrase: String): Boolean =
        verifyWith { openRaw(file, passphrase) }

    /** [verifyPassphrase] over [openRawReadOnly]: the same answer, and [file] plus any `-wal`
     *  beside it are byte-for-byte what they were afterwards. */
    fun verifyPassphraseReadOnly(file: File, passphrase: String): Boolean =
        verifyWith { openRawReadOnly(file, passphrase) }

    /** True iff [rawKey] opens [file]. Same missing-file rule as [verifyPassphrase]. */
    fun verifyRawKey(file: File, rawKey: ByteArray): Boolean =
        verifyWith { openRawKey(file, rawKey) }

    private inline fun verifyWith(open: () -> ZeticDB): Boolean = try {
        val db = open()
        try {
            db.rawQuery("SELECT count(*) FROM sqlite_master", null as Array<String>?).use { it.moveToFirst() }
            true
        } finally {
            runCatching { db.close() }
        }
    } catch (_: Exception) {
        false
    }

    /** Throws [SoilLockedException] unless [file] exists and is non-empty. */
    fun requireExisting(file: File) {
        if (!file.exists() || file.length() == 0L) {
            throw SoilLockedException("Database file is missing or empty: ${file.name}")
        }
    }

    // ── Creation-only open ───────────────────────────────────────────────────
    // The ONLY path allowed to bring a database file into existence.

    /** Create a brand-new encrypted database at [file]. Refuses to touch an existing non-empty
     *  file: creation is never a repair. */
    fun createRaw(file: File, passphrase: String): ZeticDB {
        require(!file.exists() || file.length() == 0L) { "refusing to create over an existing file: ${file.name}" }
        file.parentFile?.mkdirs()
        native
        return ZeticDB.openOrCreateDatabase(file, passphrase, null, KeepFileHandler)
    }

    // ── Probe ────────────────────────────────────────────────────────────────

    /**
     * Header-only probe. Plaintext SQLite starts with the 16-byte magic `SQLite format 3` + NUL;
     * SQLCipher encrypts the whole first page so the magic is absent. Never opens the file.
     * Missing / empty / unreadable / short → [SoilFileKind.Invalid].
     */
    fun probe(file: File): SoilFileKind {
        if (!file.exists() || file.length() == 0L) return SoilFileKind.Invalid
        val header = ByteArray(SQLITE_MAGIC.size)
        val n = try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) {
            return SoilFileKind.Invalid
        }
        if (n < header.size) return SoilFileKind.Invalid
        return if (header.contentEquals(SQLITE_MAGIC)) SoilFileKind.Plaintext else SoilFileKind.Encrypted
    }

    /** "SQLite format 3" followed by a NUL — 16 bytes. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Report corruption **without deleting**. A wrong key, a truncated file and real corruption
     * all arrive here, and none of them is a reason to remove what a person wrote: the file stays
     * for a key that fits, or for a person to look at. Logs the fact only — never a path's
     * contents, never a key.
     */
    object KeepFileHandler : DatabaseErrorHandler {
        override fun onCorruption(dbObj: ZeticDB?) {
            Log.w(TAG, "an open reported corruption; the file is kept")
        }
    }
}

/** Thrown when a database is asked to open but cannot be — missing file, or no key resolved. */
class SoilLockedException(message: String) : RuntimeException(message)
