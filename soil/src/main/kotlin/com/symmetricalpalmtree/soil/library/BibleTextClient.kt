package com.symmetricalpalmtree.soil.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.symmetricalpalmtree.soil.ext.ExtensionBinder
import com.symmetricalpalmtree.soil.ext.ExtensionCallFailed
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.IBibleText
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * **A passage's words, asked of Biblesprout**: Soil's relay for the seam's `passageText`, one
 * bind per call on the seam thread, as a recogniser is called. The service is found by
 * [Seam.ACTION_BIBLE_TEXT] in a trusted app of the same build; the first call may wait for the
 * reader to copy its Bible out of its APK. Every failure is one of `Seam.BIBLE_*`. Never logs
 * the wire or the words.
 */
object BibleTextClient {

    private const val TAG = "BibleText"

    /** Generous: the reader's first open copies 15 MB. */
    const val CALL_TIMEOUT_MS = 20_000L

    fun passageText(context: Context, wire: String): String {
        val component = find(context) ?: throw IllegalStateException(Seam.BIBLE_NO_APP)
        val bound = try {
            ExtensionBinder.bind(context, Seam.ACTION_BIBLE_TEXT, component)
        } catch (e: ExtensionCallFailed) {
            Slog.d(TAG) { "the reader could not be bound: ${e.message}" }
            throw IllegalStateException(Seam.BIBLE_FAILED)
        }
        try {
            val text = bound.call(CALL_TIMEOUT_MS) { IBibleText.Stub.asInterface(bound.binder).passageText(wire) }
            if (text.isNullOrEmpty()) throw IllegalStateException(Seam.BIBLE_UNREADABLE)
            return text
        } catch (e: IllegalStateException) {
            if (e.message == Seam.BIBLE_TOO_LONG || e.message == Seam.BIBLE_UNREADABLE) throw e
            Slog.d(TAG) { "the reader did not answer: ${e.message}" }
            throw IllegalStateException(Seam.BIBLE_FAILED)
        } catch (e: ExtensionCallFailed) {
            Slog.d(TAG) { "the reader did not answer: ${e.message}" }
            throw IllegalStateException(Seam.BIBLE_FAILED)
        } catch (e: Exception) {
            Slog.d(TAG) { "the reader failed: ${e.javaClass.simpleName}" }
            throw IllegalStateException(Seam.BIBLE_FAILED)
        } finally {
            bound.close()
        }
    }

    private fun find(context: Context): ComponentName? {
        val pm = context.packageManager
        return runCatching {
            pm.queryIntentServices(Intent(Seam.ACTION_BIBLE_TEXT), 0)
                .map { it.serviceInfo }
                .filter {
                    Seam.sameBuild(context.packageName, it.packageName) &&
                        pm.checkSignatures(context.packageName, it.packageName) == PackageManager.SIGNATURE_MATCH
                }
                .minByOrNull { it.packageName + it.name }
                ?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()
    }
}
