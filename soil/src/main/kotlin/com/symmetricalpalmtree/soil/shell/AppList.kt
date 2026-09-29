package com.symmetricalpalmtree.soil.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * One installed app that can be opened: what it is called, the activity that starts it, how it
 * looks. The activity is kept as two plain names so the list's ordering is testable on the JVM.
 */
class AppEntry(val label: String, val packageName: String, val className: String, val icon: Drawable?)

/**
 * **Every installed app with a launcher entry**, found automatically — the one list behind the
 * home screen's grid and the side menu. Once Soil holds the side bars the firmware's own menu is
 * shut everywhere, so this list is also the only quick way to the Supernote's own apps.
 *
 * Read off the main thread and kept for the process; [refresh] re-reads it when a package comes
 * or goes. Needs the `<queries>` element in the manifest: without it Android 11 shows an app
 * nothing but itself.
 */
object AppList {

    private const val TAG = "AppList"

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> get() = _apps

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        _apps.value = withContext(Dispatchers.IO) {
            try {
                val pm = app.packageManager
                val launchers = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val found = pm.queryIntentActivities(launchers, 0).map { info ->
                    val activity = info.activityInfo
                    AppEntry(
                        label = info.loadLabel(pm)?.toString().orEmpty(),
                        packageName = activity.packageName,
                        className = activity.name,
                        icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    )
                }
                arrange(found, self = app.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "the app list could not be read: ${e.javaClass.simpleName}")
                emptyList()
            }
        }
    }

    /**
     * The order the list is shown in: by name, ignoring case, with Soil itself left out (it has
     * rows of its own). An app with no name is called by its package. One entry per activity — an
     * app with two launcher entries has two. Pure.
     */
    fun arrange(found: List<AppEntry>, self: String): List<AppEntry> =
        found
            .filter { it.packageName != self }
            .map { if (it.label.isBlank()) AppEntry(it.packageName, it.packageName, it.className, it.icon) else it }
            .distinctBy { it.packageName to it.className }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, AppEntry::label)
                    .thenBy { it.packageName }
                    .thenBy { it.className },
            )

    /** Start [entry]'s activity, in a task of its own. False when it could not be started. */
    fun launch(context: Context, entry: AppEntry): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(entry.packageName, entry.className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "an app could not be started: ${e.javaClass.simpleName}")
        false
    }
}
