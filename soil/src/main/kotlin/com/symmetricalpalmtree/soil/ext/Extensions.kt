package com.symmetricalpalmtree.soil.ext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.symmetricalpalmtree.soil.paper.core.Slog

/** One installed extension service: where it is and what it calls itself. */
data class Extension(val packageName: String, val className: String, val label: String) {
    val component: ComponentName get() = ComponentName(packageName, className)
}

/**
 * **Which extensions are installed**, by point. A candidate `<service>` is kept when it is
 * exported, declares this contract's version, is signed with Soil's key and is of Soil's build.
 * Looked for each time, never remembered: an extension can be installed or removed while Soil
 * runs. The recogniser has its own finder with its languages; the rest share this one.
 */
object Extensions {

    private const val TAG = "Extensions"

    fun exporters(context: Context): List<Extension> = find(context, ExportContract.ACTION_EXPORTER)
    fun importers(context: Context): List<Extension> = find(context, ExportContract.ACTION_IMPORTER)

    fun find(context: Context, action: String): List<Extension> {
        val pm = context.packageManager
        val found = try {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(Intent(action), PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "the extensions could not be read: ${e.javaClass.simpleName}")
            return emptyList()
        }
        val kept = ArrayList<Extension>()
        for (ri in found) {
            val si = ri.serviceInfo ?: continue
            val component = ComponentName(si.packageName, si.name)
            if (!si.exported) continue
            val version = si.metaData?.getInt(ExtContract.META_API_VERSION, -1) ?: -1
            if (version != ExtContract.API_VERSION) { Slog.d(TAG) { "skip $component: contract version $version" }; continue }
            if (pm.checkSignatures(context.packageName, si.packageName) != PackageManager.SIGNATURE_MATCH) { Slog.d(TAG) { "skip $component: signature" }; continue }
            if (!ExtContract.sameBuild(context.packageName, si.packageName)) { Slog.d(TAG) { "skip $component: other build" }; continue }
            kept += Extension(si.packageName, si.name, si.applicationInfo?.loadLabel(pm)?.toString() ?: si.packageName)
        }
        Slog.d(TAG) { "$action: ${kept.size} of ${found.size} found" }
        return kept.sortedWith(compareBy({ it.label }, { it.packageName }))
    }
}
