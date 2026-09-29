package com.symmetricalpalmtree.soil.dev

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * **Debug builds only.** Proves on the device that a wrong key leaves a file exactly as it was.
 *
 * A wrong key reads as corruption, and a database library's default answer to corruption can be
 * to delete the file. Soil never relies on that default — every open passes a handler that keeps
 * the file — and this screen is the walk that shows it holds: it copies the index, opens the copy
 * with a wrong passphrase and a wrong raw key, and compares the copy's bytes before and after.
 *
 * It works on a **copy**, never on the index itself, and removes the copy afterwards.
 *
 *     adb shell am start -n com.symmetricalpalmtree.soil.dev/com.symmetricalpalmtree.soil.dev.KeepFileCheckActivity
 *     adb logcat -s SoilKeepFile
 */
class KeepFileCheckActivity : AppCompatActivity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
            setTextColor(0xFF000000.toInt())
        }
        setContentView(ScrollView(this).apply { addView(out) })
        say("Waiting for the index…")
        lifecycleScope.launch {
            val state = SoilIndex.state.first { it != SoilIndex.State.PREPARING }
            say("Index: $state")
            if (state != SoilIndex.State.READY) return@launch
            val lines = withContext(Dispatchers.IO) { check() }
            lines.forEach(::say)
        }
    }

    private fun check(): List<String> {
        val lines = ArrayList<String>()
        val index = SoilFiles.indexFile(this)
        // Fold the WAL in first, so the copy is the whole database.
        runCatching { SoilIndex.db().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null as Array<String>?).use { it.moveToFirst() } }
        val copy = File(index.parentFile, "keepfile-check.tmp")
        copy.delete()
        index.copyTo(copy)
        try {
            val before = digest(copy)
            lines += "Copy: ${copy.length()} bytes"
            lines += "hasCodec: ${net.zetetic.database.sqlcipher.SQLiteDatabase.hasCodec()}"

            lines += "Wrong passphrase opens: ${SoilCrypto.verifyPassphrase(copy, "not the passphrase")}"
            lines += after(copy, before)

            lines += "Wrong raw key opens: ${SoilCrypto.verifyRawKey(copy, ByteArray(32) { 7 })}"
            lines += after(copy, before)

            val threw = runCatching {
                SoilCrypto.openRaw(copy, "not the passphrase").use { db ->
                    db.rawQuery("SELECT count(*) FROM item", null as Array<String>?).use { it.moveToFirst() }
                }
            }.exceptionOrNull()
            lines += "Wrong-key read threw: ${threw?.javaClass?.simpleName ?: "NOTHING (unexpected)"}"
            lines += after(copy, before)

            lines += "Index itself: ${index.length()} bytes, exists=${index.exists()}"
        } finally {
            copy.parentFile?.listFiles { f -> f.name.startsWith(copy.name) }?.forEach { it.delete() }
        }
        return lines
    }

    private fun after(file: File, before: String): String =
        when {
            !file.exists() -> "  → FAIL: the file is GONE"
            digest(file) != before -> "  → FAIL: the file CHANGED (${file.length()} bytes)"
            else -> "  → kept, byte for byte"
        }

    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun say(line: String) {
        Log.i(TAG, line)
        out.append(line + "\n")
    }

    private companion object { const val TAG = "SoilKeepFile" }
}
