package com.symmetricalpalmtree.soil.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * **Which app opens which kind of item.** An app says so itself: the screen it opens an item in
 * answers [Seam.ACTION_OPEN_ITEM] and names its kind in [Seam.META_KIND].
 *
 * Soil opens an item only in an app signed with its own key and of its own build. Looked for
 * each time, never remembered: an app can be installed or removed while Soil runs.
 */
object ItemApps {

    private const val TAG = "ItemApps"

    /** One screen that offers to open items, as the device describes it. */
    data class Candidate(val packageName: String, val className: String, val kind: String?, val sameKey: Boolean)

    /**
     * The screen that opens [kind], or null when no app that may be trusted offers to. With more
     * than one, the first by package and class, so the answer does not change between two looks.
     * Pure.
     */
    fun choose(candidates: List<Candidate>, kind: String, hubPackage: String): Candidate? =
        candidates
            .filter { it.kind == kind && it.sameKey && Seam.sameBuild(hubPackage, it.packageName) }
            .minWithOrNull(compareBy(Candidate::packageName).thenBy(Candidate::className))

    fun find(context: Context, kind: String): Candidate? {
        val pm = context.packageManager
        val found = try {
            pm.queryIntentActivities(Intent(Seam.ACTION_OPEN_ITEM), PackageManager.GET_META_DATA).map { info ->
                val activity = info.activityInfo
                Candidate(
                    packageName = activity.packageName,
                    className = activity.name,
                    kind = activity.metaData?.getString(Seam.META_KIND),
                    sameKey = pm.checkSignatures(context.packageName, activity.packageName) == PackageManager.SIGNATURE_MATCH,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "the apps could not be read: ${e.javaClass.simpleName}")
            emptyList()
        }
        return choose(found, kind, context.packageName)
    }

    enum class Opened { YES, NO_APP, FAILED }

    /** Open the item in the app for its kind. What rides the Intent is the item's id. */
    fun open(context: Context, itemId: String, kind: String): Opened {
        val app = find(context, kind) ?: return Opened.NO_APP
        return try {
            context.startActivity(
                Intent(Seam.ACTION_OPEN_ITEM)
                    .setComponent(ComponentName(app.packageName, app.className))
                    .putExtra(Seam.EXTRA_ITEM_ID, itemId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Opened.YES
        } catch (e: Exception) {
            Log.w(TAG, "an item could not be opened: ${e.javaClass.simpleName}")
            Opened.FAILED
        }
    }
}
