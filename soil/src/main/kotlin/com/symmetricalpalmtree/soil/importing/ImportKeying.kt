package com.symmetricalpalmtree.soil.importing

import android.util.Log
import com.symmetricalpalmtree.soil.crypto.RekeyExport
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * An incoming file brought under this device's key: a file already under it passes through
 * after an integrity check; a plaintext file or one under another passphrase is exported into
 * a keyed sibling with the transform the rotation uses. Items have no key of their own here.
 */
object ImportKeying {

    private const val TAG = "ImportKeying"

    sealed interface Opening {
        data object Plaintext : Opening
        data class Encrypted(val passphrase: String) : Opening
    }

    suspend fun toGlobal(incoming: File, opening: Opening, global: String): File = when (opening) {
        is Opening.Encrypted -> if (opening.passphrase == global) acceptPassThrough(incoming, global) else transform(incoming, RekeyExport.sqlLiteral(opening.passphrase), global)
        Opening.Plaintext -> transform(incoming, "''", global)
    }

    private suspend fun transform(incoming: File, attachKeyLiteral: String, global: String): File =
        RekeyExport.exportAndKeyToPrimary(out = sibling(incoming), sourcePath = incoming.path, attachKeyLiteral = attachKeyLiteral, destPassphrase = global, what = "imported")

    private suspend fun acceptPassThrough(incoming: File, global: String): File = withContext(Dispatchers.IO) {
        val db = try {
            SoilCrypto.openRaw(incoming, global)
        } catch (e: Exception) {
            Log.w(TAG, "pass-through open failed: ${e.javaClass.simpleName}")
            throw IllegalStateException("the imported file could not be read back")
        }
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (integrity != "ok") throw IllegalStateException("the imported file failed its integrity check")
        } finally {
            runCatching { db.close() }
        }
        Slog.d(TAG) { "already under this device's key: pass-through accepted (${incoming.length()} bytes)" }
        incoming
    }

    private fun sibling(incoming: File): File {
        val out = File(incoming.parentFile, "${incoming.nameWithoutExtension}-keyed.soil")
        RekeyExport.rejectOutput(out)
        return out
    }
}
