package com.symmetricalpalmtree.soil.export

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.soil.ext.ExtensionBinder
import com.symmetricalpalmtree.soil.ext.PageBundle
import com.symmetricalpalmtree.soil.seam.IItemRenderer
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamPageNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The app that draws a kind's pages.** Soil cannot draw a page; the app that owns the kind
 * answers [Seam.ACTION_RENDER] with a service, and Soil binds it for an export. Trusted like a
 * screen that opens an item: Soil's key, Soil's build.
 */
object AppRenderers {

    private const val TAG = "AppRenderers"

    /** A render took longer than this and is given up: a whole notebook on an e-ink CPU. */
    const val RENDER_TIMEOUT_MS = 600_000L
    const val PAGES_TIMEOUT_MS = 30_000L

    fun find(context: Context, kind: String): ComponentName? {
        val pm = context.packageManager
        val found = try {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(Intent(Seam.ACTION_RENDER), PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "the renderers could not be read: ${e.javaClass.simpleName}")
            return null
        }
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (!si.exported) return@mapNotNull null
            if (si.metaData?.getString(Seam.META_KIND) != kind) return@mapNotNull null
            if (pm.checkSignatures(context.packageName, si.packageName) != PackageManager.SIGNATURE_MATCH) return@mapNotNull null
            if (!Seam.sameBuild(context.packageName, si.packageName)) return@mapNotNull null
            ComponentName(si.packageName, si.name)
        }.minWithOrNull(compareBy({ it.packageName }, { it.className }))
    }

    suspend fun pages(context: Context, renderer: ComponentName, itemId: String): SeamPageNames = withContext(Dispatchers.IO) {
        ExtensionBinder.once(context, Seam.ACTION_RENDER, renderer, PAGES_TIMEOUT_MS) { IItemRenderer.Stub.asInterface(it).pages(itemId) }
    }

    class Rendered(val file: File, val bytes: Long, val names: List<ExportNaming.PageName>)

    /** The bundle rendered into the export cache. Throws the app's IllegalStateException as it came. */
    suspend fun render(context: Context, renderer: ComponentName, itemId: String, pageIds: List<String>, template: Boolean, bundleVersion: Int): Rendered = withContext(Dispatchers.IO) {
        val bundle = File(ExportArtifact.freshDir(context), "$itemId.pages")
        val out = ParcelFileDescriptor.open(bundle, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        val names = try {
            ExtensionBinder.once(context, Seam.ACTION_RENDER, renderer, RENDER_TIMEOUT_MS) { IItemRenderer.Stub.asInterface(it).render(itemId, pageIds, template, bundleVersion, out) }
        } finally {
            runCatching { out.close() }
        }
        val bytes = bundle.length()
        if (bytes == 0L) throw IllegalStateException(Seam.RENDER_FAILED)
        // A bundle the app says it finished must open as one: the header is read back before the
        // bytes go anywhere.
        runCatching { PageBundle.Reader(bundle.inputStream()).use { it.pageCount } }.getOrElse { throw IllegalStateException(Seam.RENDER_FAILED) }
        Rendered(bundle, bytes, names.numbers.indices.map { ExportNaming.PageName(names.numbers[it], names.titles[it].ifEmpty { null }) })
    }

    suspend fun relabelStatements(context: Context, renderer: ComponentName, oldId: String, newId: String): List<String> = withContext(Dispatchers.IO) {
        ExtensionBinder.once(context, Seam.ACTION_RENDER, renderer, PAGES_TIMEOUT_MS) { IItemRenderer.Stub.asInterface(it).relabelStatements(oldId, newId) }
    }
}
