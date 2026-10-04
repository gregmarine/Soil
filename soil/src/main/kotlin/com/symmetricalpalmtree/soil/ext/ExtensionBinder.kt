package com.symmetricalpalmtree.soil.ext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** An extension call that did not answer: not bound, timed out, or refused. */
open class ExtensionCallFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A bound extension, blocking: the signature re-checked at the bind, the connection awaited a
 * bounded time, each call run on a thread of its own under a timeout so a slow extension holds
 * nothing longer than that, the unbind on [close]. A call the timeout left running finishes on
 * its own thread and is discarded. One bind per call for a single call; held across a per-page
 * loop. Never on the main thread.
 */
class ExtensionBinder private constructor(private val app: Context, private val connection: ServiceConnection, val binder: IBinder, private val component: ComponentName) : Closeable {

    fun <T> call(timeoutMs: Long, block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "extension-call").apply { isDaemon = true } }
        try {
            return executor.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw ExtensionCallFailed("call timeout after $timeoutMs ms", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            executor.shutdown()
        }
    }

    override fun close() {
        runCatching { app.unbindService(connection) }
        Slog.d(TAG) { "unbound ${component.flattenToShortString()}" }
    }

    companion object {
        private const val TAG = "ExtensionBinder"
        const val BIND_TIMEOUT_MS = 3_000L

        fun bind(context: Context, action: String, component: ComponentName): ExtensionBinder {
            val app = context.applicationContext
            if (app.packageManager.checkSignatures(app.packageName, component.packageName) != PackageManager.SIGNATURE_MATCH) {
                throw ExtensionCallFailed("signature no longer matches")
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
                app.bindService(Intent(action).setComponent(component), connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                throw ExtensionCallFailed("bind refused", e)
            }
            if (!bound) { runCatching { app.unbindService(connection) }; throw ExtensionCallFailed("bind returned false") }
            if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { runCatching { app.unbindService(connection) }; throw ExtensionCallFailed("bind timeout") }
            val service = binder ?: run { runCatching { app.unbindService(connection) }; throw ExtensionCallFailed("no binder") }
            return ExtensionBinder(app, connection, service, component)
        }

        /** One bind, one call, the unbind in `finally`. */
        fun <T> once(context: Context, action: String, component: ComponentName, timeoutMs: Long, block: (IBinder) -> T): T =
            bind(context, action, component).use { bound -> bound.call(timeoutMs) { block(bound.binder) } }
    }
}
