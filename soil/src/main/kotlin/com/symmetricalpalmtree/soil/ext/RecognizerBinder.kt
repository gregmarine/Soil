package com.symmetricalpalmtree.soil.ext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A recogniser call that did not answer: not bound, timed out, or refused. */
class RecognizerCallFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One bind per call, blocking, for Soil's relay on a seam thread: the signature re-checked at the
 * bind, the connection awaited a bounded time, the call run on a thread of its own under a
 * timeout so a slow recogniser holds no seam thread longer than that, the unbind in `finally`.
 * A call the timeout left running finishes on its own thread and is discarded.
 */
object RecognizerBinder {

    private const val TAG = "RecognizerBinder"
    const val BIND_TIMEOUT_MS = 3_000L

    fun <T> call(context: Context, component: ComponentName, callTimeoutMs: Long, block: (IRecognizer) -> T): T {
        val app = context.applicationContext
        if (app.packageManager.checkSignatures(app.packageName, component.packageName) != PackageManager.SIGNATURE_MATCH) {
            throw RecognizerCallFailed("signature no longer matches")
        }
        val connected = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { connected.countDown() }
            override fun onBindingDied(name: ComponentName) { connected.countDown() }
            override fun onNullBinding(name: ComponentName) { connected.countDown() }
        }
        val bound = try {
            app.bindService(Intent(ExtContract.ACTION_RECOGNIZER).setComponent(component), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            throw RecognizerCallFailed("bind refused", e)
        }
        if (!bound) { runCatching { app.unbindService(connection) }; throw RecognizerCallFailed("bind returned false") }
        try {
            if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw RecognizerCallFailed("bind timeout")
            val iface = binder?.let { IRecognizer.Stub.asInterface(it) } ?: throw RecognizerCallFailed("no binder")
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "recognizer-call").apply { isDaemon = true } }
            try {
                return executor.submit<T> { block(iface) }.get(callTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw RecognizerCallFailed("call timeout after $callTimeoutMs ms", e)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                executor.shutdown()
            }
        } finally {
            runCatching { app.unbindService(connection) }
            Slog.d(TAG) { "unbound ${component.flattenToShortString()}" }
        }
    }
}
