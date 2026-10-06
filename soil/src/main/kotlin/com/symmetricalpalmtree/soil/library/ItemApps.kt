package com.symmetricalpalmtree.soil.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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

    /** One Sprout app: its name, its icon, and the screen its own icon opens. */
    class SproutApp(val label: String, val packageName: String, val icon: Drawable?, val launch: Intent?)

    /**
     * Every trusted app that opens some kind of item, or the Bible, one entry per app, by name.
     * What the side menu lists. Read off the main thread.
     */
    fun sproutApps(context: Context): List<SproutApp> {
        val pm = context.packageManager
        return try {
            (pm.queryIntentActivities(Intent(Seam.ACTION_OPEN_ITEM), PackageManager.GET_META_DATA) +
                pm.queryIntentActivities(Intent(Seam.ACTION_OPEN_BIBLE), 0))
                .map { it.activityInfo.packageName }
                .distinct()
                .filter {
                    Seam.sameBuild(context.packageName, it) &&
                        pm.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH
                }
                .map { pkg ->
                    val info = pm.getApplicationInfo(pkg, 0)
                    SproutApp(
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = pkg,
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                        launch = pm.getLaunchIntentForPackage(pkg),
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SproutApp::label))
        } catch (e: Exception) {
            Log.w(TAG, "the Sprout apps could not be read: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    enum class Opened { YES, NO_APP, FAILED }

    /** Have the app for [kind] make a new item called [name] and open it. Only the app knows the
     *  shape of its own files, so Soil asks rather than making the file itself. */
    fun create(context: Context, kind: String, name: String): Opened {
        val app = find(context, kind) ?: return Opened.NO_APP
        return start(
            context,
            Intent(Seam.ACTION_OPEN_ITEM)
                .setComponent(ComponentName(app.packageName, app.className))
                .putExtra(Seam.EXTRA_NEW_NAME, name),
        )
    }

    /** Open the item in the app for its kind. What rides the Intent is the item's id, and for a
     *  notebook just made, the paper its first page gets ([Seam.EXTRA_TEMPLATE_PICK]). */
    fun open(context: Context, itemId: String, kind: String, templatePick: String? = null, pageId: String? = null): Opened {
        val app = find(context, kind) ?: return Opened.NO_APP
        return start(
            context,
            Intent(Seam.ACTION_OPEN_ITEM)
                .setComponent(ComponentName(app.packageName, app.className))
                .putExtra(Seam.EXTRA_ITEM_ID, itemId)
                .putExtra(Seam.EXTRA_TEMPLATE_PICK, templatePick)
                .putExtra(Seam.EXTRA_PAGE_ID, pageId),
        )
    }

    /**
     * The screen that opens the Bible ([Seam.ACTION_OPEN_BIBLE]), or null when no app that may be
     * trusted offers to. It names no kind: a passage is not an item. Pure over the candidates.
     */
    fun chooseBible(candidates: List<Candidate>, hubPackage: String): Candidate? =
        candidates
            .filter { it.sameKey && Seam.sameBuild(hubPackage, it.packageName) }
            .minWithOrNull(compareBy(Candidate::packageName).thenBy(Candidate::className))

    fun findBible(context: Context): Candidate? {
        val pm = context.packageManager
        val found = try {
            pm.queryIntentActivities(Intent(Seam.ACTION_OPEN_BIBLE), 0).map { info ->
                val activity = info.activityInfo
                Candidate(
                    packageName = activity.packageName,
                    className = activity.name,
                    kind = null,
                    sameKey = pm.checkSignatures(context.packageName, activity.packageName) == PackageManager.SIGNATURE_MATCH,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "the apps could not be read: ${e.javaClass.simpleName}")
            emptyList()
        }
        return chooseBible(found, context.packageName)
    }

    /** Open the Bible on [wire], a passage in the codec's form; the reader decodes it. */
    fun openBible(context: Context, wire: String): Opened {
        val app = findBible(context) ?: return Opened.NO_APP
        return start(
            context,
            Intent(Seam.ACTION_OPEN_BIBLE)
                .setComponent(ComponentName(app.packageName, app.className))
                .putExtra(Seam.EXTRA_BIBLE_WIRE, wire),
        )
    }

    private fun start(context: Context, intent: Intent): Opened = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Opened.YES
    } catch (e: Exception) {
        Log.w(TAG, "an item could not be opened: ${e.javaClass.simpleName}")
        Opened.FAILED
    }
}
