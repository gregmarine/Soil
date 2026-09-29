package com.symmetricalpalmtree.soil.crypto

import android.util.Log
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/**
 * **The export-and-key transform**: a copy of an encrypted database under a different key.
 *
 * The mechanism is **export-and-key, not `PRAGMA rekey`**, which proved unreliable on device: the
 * new-keyed destination is the primary connection, the old-keyed source is attached with the old
 * key, and `sqlcipher_export('main', 'old_src')` copies attachment → main.
 *
 * Two things `sqlcipher_export` does **not** do are done by hand, and each is a trap already
 * paid for:
 *
 *  - **`PRAGMA user_version` is not copied.** Without it the output is version 0 and reads as a
 *    brand-new database. [copyUserVersion] carries it over inside the same connection, and
 *    acceptance re-reads it from the finished file and refuses a mismatch.
 *  - **Nothing is accepted unverified.** The output must probe as encrypted, open under its new
 *    key, answer `PRAGMA integrity_check` with `ok`, and hold the source's `user_version`. An
 *    output that fails any of that is deleted and the transform throws.
 *
 * The source is never touched, and a failure deletes only the transform's **own** unaccepted
 * output. Passphrases appear here only as SQL string literals on a local connection
 * ([sqlLiteral]) and are never logged, never in a name, never in a message.
 */
object RekeyExport {

    private const val TAG = "RekeyExport"

    /**
     * Create [out] under [destPassphrase], attach [sourcePath] with [attachKeyLiteral] (already
     * SQL-quoted), export, carry `user_version`, then accept nothing unverified. A failure deletes
     * only the unaccepted output and throws with a path-free message built on [what].
     */
    internal suspend fun exportAndKeyToPrimary(
        out: File,
        sourcePath: String,
        attachKeyLiteral: String,
        destPassphrase: String,
        what: String,
    ): File = withContext(Dispatchers.IO) {
        val sourceVersion: Long
        val dest = try {
            SoilCrypto.createRaw(out, destPassphrase)
        } catch (e: Exception) {
            Log.w(TAG, "$what destination could not be created: ${e.javaClass.simpleName}")
            throw IllegalStateException("the $what copy could not be made")
        }
        try {
            dest.execSQL("ATTACH DATABASE ${sqlLiteral(sourcePath)} AS old_src KEY $attachKeyLiteral")
            try {
                sourceVersion = queryLong(dest, "PRAGMA old_src.user_version")
                dest.rawQuery("SELECT sqlcipher_export('main', 'old_src')", NO_ARGS).use { it.moveToFirst() }
                copyUserVersion(dest, from = "old_src", to = "main")
            } finally {
                dest.execSQL("DETACH DATABASE old_src")
            }
        } catch (e: Exception) {
            rejectOutput(out)
            Log.w(TAG, "$what transform failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the $what copy could not be made")
        } finally {
            runCatching { dest.close() }
        }

        // Acceptance: the destination passphrase opens it, it is intact, and the version travelled.
        if (SoilCrypto.probe(out) != SoilFileKind.Encrypted) {
            rejectOutput(out)
            throw IllegalStateException("the $what copy did not come out encrypted")
        }
        val check = SoilCrypto.openRaw(out, destPassphrase)
        try {
            requireIntact(queryString(check, "PRAGMA integrity_check"))
            requireVersion(queryLong(check, "PRAGMA main.user_version"), sourceVersion)
        } catch (e: Exception) {
            rejectOutput(out)
            throw e
        } finally {
            runCatching { check.close() }
        }
        Slog.d(TAG) { "$what transform accepted (${out.length()} bytes)" }
        out
    }

    private fun requireIntact(integrity: String?) {
        if (integrity != "ok") {
            Log.w(TAG, "integrity check answered ${integrity?.take(40) ?: "nothing"}")
            throw IllegalStateException("the copy failed its integrity check")
        }
    }

    /** Pinned at acceptance too: a version-less copy reads as a new database. */
    private fun requireVersion(actual: Long, expected: Long) {
        if (actual != expected) {
            Log.w(TAG, "user_version is $actual, source said $expected")
            throw IllegalStateException("the copy lost its schema version")
        }
    }

    /** `sqlcipher_export` copies data, not `PRAGMA user_version` — carry it over by hand. */
    private fun copyUserVersion(db: ZeticDB, from: String, to: String) {
        val version = queryLong(db, "PRAGMA $from.user_version")
        db.execSQL("PRAGMA $to.user_version = $version")
    }

    /** Remove an output that was never accepted, and its sidecars. Never pointed at an input. */
    internal fun rejectOutput(out: File) {
        out.parentFile?.listFiles { f -> f.name.startsWith(out.name) }?.forEach { runCatching { it.delete() } }
    }

    private fun queryLong(db: ZeticDB, sql: String): Long =
        db.rawQuery(sql, NO_ARGS).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    private fun queryString(db: ZeticDB, sql: String): String? =
        db.rawQuery(sql, NO_ARGS).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Typed, so the call never lands on `rawQuery(String, Object...)`. */
    private val NO_ARGS: Array<String>? = null

    /**
     * A string as a single-quoted SQL literal, quotes doubled. Pure and pinned by test: the
     * ATTACH statement above carries a path and a key this way.
     */
    fun sqlLiteral(s: String): String = "'" + s.replace("'", "''") + "'"
}
