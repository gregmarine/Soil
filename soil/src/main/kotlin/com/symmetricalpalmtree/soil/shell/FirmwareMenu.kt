package com.symmetricalpalmtree.soil.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * **Holds the firmware's own side menu shut**, so that a swipe down of the right bar opens Soil's.
 *
 * The firmware's launcher exports a service with no caller check, and one of its calls locks the
 * side menu. That is the whole mechanism, and it rests on the launcher's internals: a firmware
 * update can change it. Everything here fails quietly — if the lock stops working the firmware's
 * menu simply opens as it always did, and Soil carries on beside it.
 *
 * **Only the side menu is ever locked.** The pull-down status bar has a lock of its own, which is
 * global: locking it would take the status bar away from every app. It is released on connect
 * and on the way out, in case anything left it shut, and never locked.
 *
 * The lock does not last. The launcher clears it on every change of the app in front, so it is
 * put back after each one and again as the bar is touched.
 */
class FirmwareMenu(private val context: Context) {

    private var launcher: IBinder? = null

    val connected: Boolean get() = launcher != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            launcher = service
            Slog.d(TAG) { "the firmware launcher is connected" }
            transact(LOCK_STATUS_BAR, 0)
            lock()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            launcher = null
            Slog.d(TAG) { "the firmware launcher went away" }
        }
    }

    fun connect() {
        val intent = Intent().setComponent(ComponentName(LAUNCHER, "$LAUNCHER.service.GestureService"))
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) Log.w(TAG, "the firmware launcher could not be bound; its menu is not held shut")
    }

    /** Release the menu and let go. The firmware's menu is its own again. */
    fun disconnect() {
        runCatching { unlock() }
        runCatching { if (launcher != null) context.unbindService(connection) }
        launcher = null
    }

    fun lock(): Boolean = transact(LOCK_SIDE_MENU, 1)

    private fun unlock() {
        transact(LOCK_SIDE_MENU, 0)
        transact(LOCK_STATUS_BAR, 0)
    }

    private fun transact(code: Int, argument: Int): Boolean {
        val binder = launcher ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(argument)
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "the firmware launcher refused call $code: ${e.javaClass.simpleName}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val TAG = "FirmwareMenu"

        const val LAUNCHER = "com.ratta.supernote.launcher"
        private const val DESCRIPTOR = "com.ratta.supernote.launcher.IGestureInterface"

        /** The launcher's own call numbers. */
        private const val LOCK_SIDE_MENU = 4
        private const val LOCK_STATUS_BAR = 5

        /** What the firmware says as it refreshes the screen: the right bar was swiped up. */
        const val ACTION_REFRESH = "com.ratta.supernote.launcher.flashscreen"

        /** What the firmware says as it shows its side menu, or its status bar. */
        const val ACTION_MENU_STATE = "com.ratta.supernote.launcher.slidebarstatusbarstate"
        const val EXTRA_SHOW = "show"
    }
}
