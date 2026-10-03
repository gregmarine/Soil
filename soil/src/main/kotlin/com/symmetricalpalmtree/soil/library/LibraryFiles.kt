package com.symmetricalpalmtree.soil.library

import android.content.Context
import com.symmetricalpalmtree.soil.crypto.DerivedKeyStore
import com.symmetricalpalmtree.soil.crypto.Sidecars
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * What a delete does to the disk once the index row is marked: the item's file and its sidecars
 * go, and so does the key derived for it. A key left behind would open a future file that
 * happened to take the same id with a key derived from one that no longer exists. Never throws.
 * **Blocking**: IO only.
 */
object LibraryFiles {

    private const val TAG = "LibraryFiles"

    fun deleteItemFile(context: Context, itemId: String) {
        val app = context.applicationContext
        val file = SoilFiles.itemFile(app, itemId)
        for (side in Sidecars.of(file)) runCatching { side.delete() }
        if (file.exists() && !file.delete()) Slog.d(TAG) { "an item's file would not delete" }
        runCatching { DerivedKeyStore.remove(app, itemId) }
    }
}
