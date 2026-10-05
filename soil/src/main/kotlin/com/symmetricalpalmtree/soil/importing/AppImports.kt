package com.symmetricalpalmtree.soil.importing

import android.content.ComponentName
import android.content.Context
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import com.symmetricalpalmtree.soil.export.AppRenderers
import com.symmetricalpalmtree.soil.library.LibraryFiles
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **An item made from a file its app takes in**: a document from a `.md`. Two things come here:
 * an import of a picked file, and another app's content made into this kind (a notebook's words
 * into a document). The item is made empty as New makes one, the app writes the file into it,
 * and an app that refuses the file leaves nothing behind: the empty item is taken away again.
 */
object AppImports {

    /** An app that takes files in, and what it says of them. */
    class Taker(val kind: String, val renderer: ComponentName, val info: SeamRenderInfo)

    /** Every app that takes files in as new items of its kind. Each renderer is asked what it is. */
    suspend fun takers(context: Context): List<Taker> {
        val renderers = withContext(Dispatchers.IO) { AppRenderers.all(context) }
        val kept = ArrayList<Taker>()
        for ((kind, renderer) in renderers) {
            val said = AppRenderers.describe(context, renderer)
            if (said.importExtensions.isNotEmpty()) kept += Taker(kind, renderer, said)
        }
        return kept
    }

    /** The app that takes a file with [fileExtension], or null. */
    suspend fun takerOf(context: Context, fileExtension: String): Taker? =
        takers(context).firstOrNull { taker -> taker.info.importExtensions.any { it.equals(fileExtension, ignoreCase = true) } }

    /**
     * Make the item and have the app write [file] into it. Answers the new item's id. Throws
     * what the app threw (an `IllegalStateException` carrying one of `Seam.INGEST_*`) after
     * taking the empty item away.
     */
    suspend fun make(context: Context, kind: String, renderer: ComponentName, name: String, parentId: String, fileExtension: String, file: File): String {
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // The file first: a row with no file is an item that cannot be opened.
            ItemFiles.createEmpty(context, id, name, now, kind)
            IndexStore().insert(id, kind, name, now, parentId)
        }
        try {
            AppRenderers.ingest(context, renderer, id, fileExtension, file)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { retire(context, id) }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { retire(context, id) }
            throw e
        }
        return id
    }

    private fun retire(context: Context, id: String) {
        if (LibraryStore().deleteItem(id)) LibraryFiles.deleteItemFile(context, id)
    }
}
