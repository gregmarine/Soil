package com.symmetricalpalmtree.soil.ext.mlkit

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.symmetricalpalmtree.soil.ext.ExtContract
import java.util.concurrent.TimeUnit

/**
 * The models and the ML Kit clients, one per language, for the process's life. A model lives in
 * this extension's own storage, managed by ML Kit.
 *
 * Readiness is one async chain per language: `isModelDownloaded` → `download` (only when needed)
 * → build the client. **Only [prepare] may start it**: Soil's apps ask the person before a
 * download. [status] never waits on ML Kit; [awaitReady] waits for a chain already in flight,
 * inside the caller's budget, and never starts one.
 *
 * Once a chain has seen a model on disk a flag is kept, so a fresh process builds the client at
 * once ([warmUp]) with no ML Kit check. An engine failure on such a client is verified against
 * the disk before the flag is forgotten: a slow first inference is not "gone".
 *
 * Every method runs on Binder threads. Logs carry class names, counts and durations, never text.
 */
internal object ModelManager {

    private const val TAG = "ModelManager"
    private const val PREFS = "model"
    private const val PRIME_AWAIT_MS = 30_000L

    private class Slot(val tag: String) {
        val model: DigitalInkRecognitionModel? = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.let { DigitalInkRecognitionModel.builder(it).build() }
        } catch (e: Exception) {
            Log.e(TAG, "model identifier failed for $tag: ${e.javaClass.simpleName}")
            null
        }
        @Volatile var recognizer: DigitalInkRecognizer? = null
        @Volatile var chain: Task<Void>? = null
        @Volatile var shortcut = false
        @Volatile var verifying = false
        val lock = Any()
        val key: String get() = "present:$tag"
    }

    private val slots = HashMap<String, Slot>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun slot(tag: String): Slot = slots.getOrPut(tag) { Slot(tag) }

    fun status(tag: String): Int {
        val s = slot(tag)
        if (s.recognizer != null) return ExtContract.STATUS_READY
        if (s.model == null) return ExtContract.STATUS_UNAVAILABLE
        val c = s.chain
        return if (c != null && !c.isComplete) ExtContract.STATUS_DOWNLOADING else ExtContract.STATUS_NEEDS_DOWNLOAD
    }

    /** With the model remembered as present, build the client now; otherwise nothing until [prepare]. */
    fun warmUp(tag: String) {
        val s = slot(tag)
        if (s.recognizer != null) return
        val m = s.model ?: return
        synchronized(s.lock) {
            if (s.recognizer != null) return
            if (prefs?.getBoolean(s.key, false) != true) return
            if (BuildConfig.DEBUG) Log.d(TAG, "$tag remembered as present: building the client directly")
            s.shortcut = true
            buildClient(s, m)
            s.chain = Tasks.forResult<Void>(null)
        }
    }

    /** Start, or restart after a failure, the ensure-ready chain. Idempotent. The only download starter. */
    fun prepare(tag: String) {
        val s = slot(tag)
        if (s.recognizer != null) return
        val m = s.model ?: return
        synchronized(s.lock) {
            if (s.recognizer != null) return
            val inFlight = s.chain
            if (inFlight != null && !inFlight.isComplete) return
            if (BuildConfig.DEBUG) Log.d(TAG, "$tag: ensure-ready chain start")
            val t0 = System.currentTimeMillis()
            val manager = RemoteModelManager.getInstance()
            s.chain = manager.isModelDownloaded(m)
                .onSuccessTask { downloaded ->
                    if (downloaded == true) Tasks.forResult<Void>(null)
                    else manager.download(m, DownloadConditions.Builder().build())
                }
                .onSuccessTask {
                    prefs?.edit()?.putBoolean(s.key, true)?.apply()
                    s.shortcut = false
                    buildClient(s, m)
                    if (BuildConfig.DEBUG) Log.d(TAG, "$tag ready (${System.currentTimeMillis() - t0} ms)")
                    Tasks.forResult<Void>(null)
                }
                .addOnFailureListener { e -> Log.w(TAG, "$tag ensure-ready failed: ${e.javaClass.simpleName}") }
        }
    }

    /** The client, waiting up to [timeoutMs] for a chain already in flight; null when it is not ready by then. */
    fun awaitReady(tag: String, timeoutMs: Long): DigitalInkRecognizer? {
        val s = slot(tag)
        s.recognizer?.let { return it }
        warmUp(tag)
        s.recognizer?.let { return it }
        val c = s.chain ?: return null
        if (c.isComplete) return s.recognizer
        try {
            Tasks.await(c, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "awaitReady: ${e.javaClass.simpleName} after $timeoutMs ms")
        }
        return s.recognizer
    }

    /** The engine failed on a real call. A shortcut-built client's model may be gone: verify on disk first. */
    fun onEngineFailure(tag: String) {
        val s = slot(tag)
        if (!s.shortcut || s.verifying) return
        val m = s.model ?: return
        synchronized(s.lock) {
            if (!s.shortcut || s.verifying) return
            s.verifying = true
        }
        Log.w(TAG, "$tag: engine failure on the remembered model, verifying it is still on disk")
        RemoteModelManager.getInstance().isModelDownloaded(m)
            .addOnSuccessListener { downloaded ->
                synchronized(s.lock) {
                    s.verifying = false
                    if (downloaded == true) {
                        s.shortcut = false
                    } else {
                        Log.w(TAG, "$tag: model gone, forgetting it")
                        prefs?.edit()?.remove(s.key)?.apply()
                        s.shortcut = false
                        s.recognizer?.close()
                        s.recognizer = null
                        s.chain = null
                    }
                }
            }
            .addOnFailureListener { e ->
                synchronized(s.lock) { s.verifying = false }
                Log.w(TAG, "$tag: model re-check failed: ${e.javaClass.simpleName}")
            }
    }

    private fun buildClient(s: Slot, m: DigitalInkRecognitionModel) {
        val built: DigitalInkRecognizer
        synchronized(s.lock) {
            if (s.recognizer != null) return
            built = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
            s.recognizer = built
        }
        prime(built)
    }

    /** One throwaway inference off the Binder thread, so the first real call does not pay the model load. */
    private fun prime(r: DigitalInkRecognizer) {
        Thread({
            try {
                val stroke = Ink.Stroke.builder().addPoint(Ink.Point.create(10f, 10f)).addPoint(Ink.Point.create(12f, 12f)).build()
                Tasks.await(r.recognize(Ink.builder().addStroke(stroke).build()), PRIME_AWAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "engine prime failed: ${e.javaClass.simpleName}")
            }
        }, "mlkit-prime").apply { isDaemon = true }.start()
    }
}
