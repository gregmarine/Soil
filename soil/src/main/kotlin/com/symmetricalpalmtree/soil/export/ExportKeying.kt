package com.symmetricalpalmtree.soil.export

import android.util.Log
import com.symmetricalpalmtree.soil.crypto.RekeyExport
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The keying an export asked for, applied to the cache copy: Keep is the copy itself; a new
 * passphrase re-keys it through the transform the rotation uses; plain exports it into an
 * unkeyed sibling and reads that back before accepting it. Every transform works on the cache
 * copy alone and never touches the library's file.
 */
object ExportKeying {

    private const val TAG = "ExportKeying"

    enum class Plan { KEEP, REKEY, PLAIN }

    fun plan(keying: String?, hasNewPassphrase: Boolean): Plan = when (keying) {
        null, ExportContract.KEYING_KEEP -> Plan.KEEP
        ExportContract.KEYING_REKEY -> { require(hasNewPassphrase) { "rekey planned with no passphrase collected" }; Plan.REKEY }
        ExportContract.KEYING_PLAIN -> Plan.PLAIN
        else -> throw IllegalArgumentException("unknown keying value")
    }

    suspend fun apply(artifact: File, passphrase: String, plan: Plan, newPassphrase: String?): File = when (plan) {
        Plan.KEEP -> artifact
        Plan.REKEY -> RekeyExport.exportAndKeyToPrimary(
            out = sibling(artifact, "rekeyed"), sourcePath = artifact.path,
            attachKeyLiteral = RekeyExport.sqlLiteral(passphrase), destPassphrase = checkNotNull(newPassphrase) { "rekey with no passphrase" }, what = "re-keyed",
        )
        Plan.PLAIN -> plain(artifact, passphrase)
    }

    private suspend fun plain(artifact: File, passphrase: String): File = withContext(Dispatchers.IO) {
        val out = sibling(artifact, "plain")
        val sourceVersion: Long
        val src = SoilCrypto.openRaw(artifact, passphrase)
        try {
            sourceVersion = queryLong(src, "PRAGMA main.user_version")
            src.execSQL("ATTACH DATABASE ${RekeyExport.sqlLiteral(out.path)} AS plaintext KEY ''")
            try {
                src.rawQuery("SELECT sqlcipher_export('plaintext')", null).use { it.moveToFirst() }
                src.execSQL("PRAGMA plaintext.user_version = $sourceVersion")
            } finally {
                src.execSQL("DETACH DATABASE plaintext")
            }
        } catch (e: Exception) {
            RekeyExport.rejectOutput(out)
            Log.w(TAG, "plain transform failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the plaintext copy could not be made")
        } finally {
            runCatching { src.close() }
        }
        if (SoilCrypto.probe(out) != SoilFileKind.Plaintext) {
            RekeyExport.rejectOutput(out)
            throw IllegalStateException("the plaintext copy did not come out plaintext")
        }
        verifyPlaintext(out, sourceVersion)
        Slog.d(TAG) { "plain transform accepted (${out.length()} bytes)" }
        out
    }

    private fun verifyPlaintext(out: File, sourceVersion: Long) {
        val db = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(out.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY) { }
        } catch (e: Exception) {
            RekeyExport.rejectOutput(out)
            Log.w(TAG, "plaintext verify open failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the plaintext copy could not be read back")
        }
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (integrity != "ok") throw IllegalStateException("the copy failed its integrity check")
            val version = db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            if (version != sourceVersion) throw IllegalStateException("the copy lost its schema version")
        } catch (e: Exception) {
            RekeyExport.rejectOutput(out)
            throw e
        } finally {
            runCatching { db.close() }
        }
    }

    private fun queryLong(db: net.zetetic.database.sqlcipher.SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    private fun sibling(artifact: File, suffix: String): File {
        val out = File(artifact.parentFile, "${artifact.nameWithoutExtension}-$suffix.soil")
        RekeyExport.rejectOutput(out)
        return out
    }
}
