package com.symmetricalpalmtree.soil.ext.mlkit

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.symmetricalpalmtree.soil.ext.ExtContract
import com.symmetricalpalmtree.soil.ext.HostCallerCheck
import com.symmetricalpalmtree.soil.ext.IRecognizer
import com.symmetricalpalmtree.soil.ext.InkStroke
import java.util.concurrent.TimeoutException

/**
 * Soil's recogniser point, with Google ML Kit digital ink recognition. English (`en-US`) is the
 * one language built. Bound by Soil, never launched by a person; every method proves the caller
 * is Soil first. The calls are stateless; the models and clients belong to [ModelManager].
 *
 * AIDL methods arrive on Binder threads and the recognition runs on them, under a budget just
 * below Soil's own timeouts, so the extension stops at its deadline instead of grinding on after
 * Soil has given up. Only marshalable exceptions leave a stub. Logs never carry text.
 */
class RecognizerService : Service() {

    private val binder = object : IRecognizer.Stub() {

        override fun status(languageTag: String?): Int {
            enforce()
            return ModelManager.status(language(languageTag))
        }

        override fun prepare(languageTag: String?) {
            enforce()
            ModelManager.prepare(language(languageTag))
        }

        override fun recognizeInk(languageTag: String?, strokes: List<InkStroke>?, areaWidth: Float, areaHeight: Float, preContext: String?): String {
            enforce()
            val tag = language(languageTag)
            val t0 = System.currentTimeMillis()
            val ink = checkInk(strokes)
            require(areaWidth > 0f && areaHeight > 0f) { "non-positive writing area" }
            val recognizer = ready(tag, INK_READY_WAIT_MS)
            val text = engine(tag) { MlKitEngine.recognizeInk(recognizer, ink, areaWidth, areaHeight, preContext ?: "", t0 + INK_BUDGET_MS) }
            if (BuildConfig.DEBUG) Log.d(TAG, "recognizeInk: ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms")
            return text
        }

        override fun recognizePage(languageTag: String?, strokes: List<InkStroke>?, pageWidth: Float, pageHeight: Float): String {
            enforce()
            val tag = language(languageTag)
            val t0 = System.currentTimeMillis()
            val ink = checkInk(strokes)
            require(pageWidth > 0f && pageHeight > 0f) { "non-positive page size" }
            val recognizer = ready(tag, PAGE_READY_WAIT_MS)
            val text = engine(tag) { MlKitEngine.recognizePage(recognizer, ink, pageWidth, pageHeight, t0 + PAGE_BUDGET_MS) }
            if (BuildConfig.DEBUG) Log.d(TAG, "recognizePage: ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms")
            return text
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.SOIL_PACKAGE)

    private fun language(tag: String?): String {
        require(tag != null && tag in LANGUAGES) { "not a language of this recogniser" }
        return tag
    }

    private fun checkInk(strokes: List<InkStroke>?): List<InkStroke> {
        requireNotNull(strokes) { "strokes is null" }
        require(strokes.size <= ExtContract.MAX_INK_STROKES) { "too many strokes" }
        var points = 0L
        for (s in strokes) {
            requireNotNull(s) { "null stroke" }
            points += s.size
        }
        require(points <= ExtContract.MAX_INK_POINTS) { "too many points" }
        return strokes
    }

    private fun ready(tag: String, waitMs: Long) =
        ModelManager.awaitReady(tag, waitMs) ?: throw IllegalStateException(ExtContract.NOT_READY)

    private inline fun engine(tag: String, block: () -> String): String = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: TimeoutException) {
        Log.w(TAG, "recognition timed out")
        throw IllegalStateException("recognition timed out")
    } catch (e: InterruptedException) {
        throw IllegalStateException("recognition interrupted")
    } catch (e: Exception) {
        Log.w(TAG, "engine failure: ${e.javaClass.simpleName}")
        ModelManager.onEngineFailure(tag)
        throw IllegalStateException("recognition failed: ${e.javaClass.simpleName}")
    }

    override fun onCreate() {
        super.onCreate()
        ModelManager.init(this)
        for (tag in LANGUAGES) ModelManager.warmUp(tag)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "RecognizerService"
        val LANGUAGES = listOf("en-US")
        const val INK_READY_WAIT_MS = 6_000L
        const val PAGE_READY_WAIT_MS = 22_000L
        const val INK_BUDGET_MS = 9_500L
        const val PAGE_BUDGET_MS = 28_000L
    }
}
