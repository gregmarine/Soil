package com.symmetricalpalmtree.soil.stranger

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.TextView
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * Binds Soil's seam and says what happened, on the screen and in the log (`SeamCheck`).
 *
 * Built twice. The **friend**, signed with Soil's key, is answered with the seam's version and
 * whether the library is unlocked. The **stranger**, signed with a key of its own, is refused at
 * the bind: Android does not grant it Soil's signature permission, so none of Soil's code runs.
 */
class SeamCheckActivity : Activity() {

    private lateinit var out: TextView
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val result = runCatching { ISoilSeam.Stub.asInterface(service).hello() }
            say(
                result.fold(
                    onSuccess = { "ANSWERED: seam version ${it.seamVersion}, library unlocked: ${it.libraryUnlocked}" },
                    onFailure = { "REFUSED at the call: ${it.javaClass.simpleName}" },
                ),
            )
        }

        override fun onServiceDisconnected(name: ComponentName) = say("the seam went away")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(48, 48, 48, 48)
        }
        setContentView(out)
        say("I am ${BuildConfig.FLAVOR}, asking ${BuildConfig.SOIL_PACKAGE}")
        say("holds the permission: ${checkSelfPermission(Seam.permissionFor(BuildConfig.SOIL_PACKAGE)) == android.content.pm.PackageManager.PERMISSION_GRANTED}")

        val intent = Intent().setClassName(BuildConfig.SOIL_PACKAGE, Seam.SERVICE_CLASS)
        val started = try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE).also { bound = it }
        } catch (e: SecurityException) {
            say("REFUSED at the bind: SecurityException")
            return
        }
        if (!started) say("NOT BOUND: Soil is not installed, or the seam is not there")
    }

    override fun onDestroy() {
        if (bound) runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        out.append(line + "\n")
    }

    private companion object { const val TAG = "SeamCheck" }
}
