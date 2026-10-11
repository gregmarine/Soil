package com.symmetricalpalmtree.soil.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * **The device's shared storage by path** (Greg, 2026-10-10): with Android's *All files access*
 * switched on for Sproutscape, a one-time toggle on a system screen, Soil reads and writes
 * `Documents`, `Note`, `EXPORT` and the rest as files, and its own browser stands where the
 * Android picker was, which has no Cancel and strands a person on this device. Without the
 * access the Android picker stays, so nothing regresses. The permission is declared in the
 * manifest; whether it is on is asked at every use, never cached: the person can switch it off
 * in Settings at any time.
 */
object LocalStorage {

    private const val TAG = "LocalStorage"

    /**
     * The toggle was opened from this process. A process started before the access was granted
     * keeps the narrow view of the storage it was born with, and Android does not restart Soil
     * on the toggle (walked, 2026-10-10), so Soil restarts itself once the access is seen: a
     * plain process death, after which Home comes back and the side menu's service rebinds
     * (a force-stop would drop it; this is not one).
     */
    @Volatile private var grantOpened = false

    fun hasAccess(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** The root the browser opens on: the shared storage, `/storage/emulated/0` on the Nomad. */
    fun root(): File = Environment.getExternalStorageDirectory()

    /** The system screen for this app's toggle; the list of every app when that one is missing. False when neither opens. */
    fun openSettings(activity: Activity): Boolean {
        grantOpened = true
        val mine = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + activity.packageName))
        val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (intent in listOf(mine, all)) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "settings screen did not open: ${e.javaClass.simpleName}")
            }
        }
        return false
    }

    /**
     * Whether the access arrived while this process stood: then the restart dialog is shown and
     * true answered; the caller ends its beat. False otherwise, nothing shown.
     */
    fun settleAfterGrant(activity: Activity): Boolean {
        if (!grantOpened || !hasAccess()) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        grantOpened = false
        com.symmetricalpalmtree.soil.paper.core.Dialogs.style(
            androidx.appcompat.app.AlertDialog.Builder(activity).setTitle(com.symmetricalpalmtree.soil.R.string.files_restart_title).setMessage(com.symmetricalpalmtree.soil.R.string.files_restart_body)
                .setPositiveButton(com.symmetricalpalmtree.soil.R.string.files_restart_now) { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
                .setCancelable(false).create(),
        ).show()
        return true
    }

    /** What the crumb calls the root. */
    fun label(context: Context): String = context.getString(com.symmetricalpalmtree.soil.R.string.files_device)
}
