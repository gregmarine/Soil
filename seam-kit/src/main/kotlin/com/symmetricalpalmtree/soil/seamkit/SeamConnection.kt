package com.symmetricalpalmtree.soil.seamkit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.Seam
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why Soil could not be reached. */
class SeamUnavailable(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Soil is not installed, or this app is not signed with Soil's key. */
        NOT_BOUND,
        /** The bind was accepted and Soil did not answer in time. */
        TIMED_OUT,
    }
}

/**
 * **An app's way to Soil.** One bind for the process, made on first use and made again when Soil
 * has gone away.
 *
 * The app names its Soil when it is built: the permission that guards the seam is named after
 * the install, so a debug app talks to the debug Soil and a release app to the release one.
 *
 * Every call on what [seam] answers crosses to Soil and waits. `Dispatchers.IO` only.
 */
class SeamConnection(context: Context, private val soilPackage: String) {

    private val app = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var bound: ISoilSeam? = null
    private var waiting: CompletableDeferred<ISoilSeam>? = null

    /** Told, on the main thread, each time Soil goes away. Whatever the app held of Soil's is
     *  dead: an open item must be opened again. */
    @Volatile
    var onLost: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val seam = ISoilSeam.Stub.asInterface(service)
            bound = seam
            waiting?.complete(seam)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = null
            onLost?.invoke()
        }

        override fun onBindingDied(name: ComponentName) {
            bound = null
            runCatching { app.unbindService(this) }
            onLost?.invoke()
        }
    }

    /** Soil, bound if it was not. Throws [SeamUnavailable]. */
    suspend fun seam(): ISoilSeam {
        bound?.let { return it }
        return mutex.withLock {
            bound?.let { return@withLock it }
            val pending = CompletableDeferred<ISoilSeam>()
            waiting = pending
            val accepted = withContext(Dispatchers.Main) {
                try {
                    app.bindService(
                        Intent().setClassName(soilPackage, Seam.SERVICE_CLASS),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                } catch (_: SecurityException) {
                    false
                }
            }
            if (!accepted) {
                // A refused bind still has to be given back, or the system keeps it.
                withContext(Dispatchers.Main) { runCatching { app.unbindService(connection) } }
                throw SeamUnavailable(SeamUnavailable.Reason.NOT_BOUND)
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { pending.await() }
                ?: throw SeamUnavailable(SeamUnavailable.Reason.TIMED_OUT)
        }
    }

    private companion object {
        /** Soil may have to be started, and opens nothing while it binds. */
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
