package com.symmetricalpalmtree.soil.data

import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.crypto.SoilLockedException
import com.symmetricalpalmtree.soil.paper.core.Slog
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import java.io.File

/** The key a file is opened with. A value of this type is never logged and never leaves Soil. */
sealed class FileKey {
    /** The passphrase itself: SQLCipher runs its own key derivation, which takes seconds. */
    class Passphrase(val value: String) : FileKey()

    /** A key already derived for this one file: the derivation is skipped. */
    class Raw(val value: ByteArray) : FileKey()
}

/**
 * Opens one of Soil's databases and brings it to its [Schema]. Everything goes through
 * [SoilCrypto], so nothing here can open without a key, create over a file, or delete one.
 *
 * There is deliberately no open helper underneath: a helper resolves names to paths of its own
 * choosing, creates what it does not find, and can delete a file it judges too old. Soil decides
 * each of those in the open.
 *
 * **Blocking.** IO only.
 */
object SoilDb {

    private const val TAG = "SoilDb"

    /** Create [file] under [passphrase] with [schema]. Refuses an existing non-empty file. */
    fun create(file: File, passphrase: String, schema: Schema): ZeticDB =
        prepared(SoilCrypto.createRaw(file, passphrase), schema, NO_CHECK)

    /** Create [file] under [passphrase] with no steps run: version 0, for the app that owns the
     *  kind to bring to its own schema at its first open. Refuses an existing non-empty file. */
    fun createUnversioned(file: File, passphrase: String): ZeticDB {
        val db = SoilCrypto.createRaw(file, passphrase)
        return try {
            configure(db)
            db
        } catch (t: Throwable) {
            runCatching { db.close() }
            throw t
        }
    }

    /**
     * Open the existing [file]. The header is probed first and the file is opened only when it
     * reads as encrypted: a plaintext or unreadable file is refused, never opened to find out.
     *
     * [check] sees the file once it is open and **before any step of [schema] runs**. When it
     * throws, the file is closed as it was found: a file of another kind is never migrated.
     */
    fun open(file: File, key: FileKey, schema: Schema, check: (ZeticDB) -> Unit = NO_CHECK): ZeticDB {
        SoilCrypto.requireExisting(file)
        if (SoilCrypto.probe(file) != SoilFileKind.Encrypted) {
            throw SoilLockedException("${file.name} is not an encrypted database")
        }
        val db = when (key) {
            is FileKey.Passphrase -> SoilCrypto.openRaw(file, key.value)
            is FileKey.Raw -> SoilCrypto.openRawKey(file, key.value)
        }
        return prepared(db, schema, check)
    }

    /** Configure and migrate, closing [db] if either throws so no connection is left behind. */
    private fun prepared(db: ZeticDB, schema: Schema, check: (ZeticDB) -> Unit): ZeticDB = try {
        configure(db)
        check(db)
        migrate(db, schema)
        db
    } catch (t: Throwable) {
        runCatching { db.close() }
        throw t
    }

    private fun configure(db: ZeticDB) {
        // A cascade is only honoured with this on, and it is off by default on every connection.
        db.setForeignKeyConstraintsEnabled(true)
        if (!db.enableWriteAheadLogging()) Slog.d(TAG) { "write-ahead logging was not enabled" }
        db.rawQuery("PRAGMA wal_autocheckpoint = 100", NO_ARGS).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout = 5000", NO_ARGS).use { it.moveToFirst() }
    }

    /** Each pending step in its own transaction, the version stamped inside it: a step either
     *  lands whole with its number or not at all. */
    private fun migrate(db: ZeticDB, schema: Schema) {
        for ((version, statements) in schema.pending(db.version)) {
            db.beginTransaction()
            try {
                statements.forEach { db.execSQL(it) }
                db.version = version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Slog.d(TAG) { "${schema.name} is at version $version" }
        }
    }

    /** Fold the WAL into the main file. Never throws. */
    fun checkpoint(db: ZeticDB) {
        runCatching {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", NO_ARGS).use { it.moveToFirst() }
        }
    }

    private val NO_CHECK: (ZeticDB) -> Unit = {}

    /** Typed, so the call never lands on `rawQuery(String, Object...)`. */
    private val NO_ARGS: Array<String>? = null
}
