package com.symmetricalpalmtree.soil.library

import android.content.Context
import com.symmetricalpalmtree.soil.templates.SortField
import com.symmetricalpalmtree.soil.templates.SortOrder

/**
 * What this device remembers of the library: the sort, and the folder it was standing in. Ids
 * and enum names only, never a name: these prefs are plaintext. Nothing here is trusted as still
 * existing; a folder that is gone lands at the root.
 */
class LibraryPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var sortField: SortField
        get() = prefs.getString(KEY_SORT_FIELD, null)?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME
        set(value) { prefs.edit().putString(KEY_SORT_FIELD, value.name).apply() }

    var sortOrder: SortOrder
        get() = prefs.getString(KEY_SORT_ORDER, null)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ASC
        set(value) { prefs.edit().putString(KEY_SORT_ORDER, value.name).apply() }

    /** The folder the library was standing in; `''` is the root. */
    var folderId: String
        get() = prefs.getString(KEY_FOLDER, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_FOLDER, value).apply() }

    private companion object {
        const val FILE = "soil_library"
        const val KEY_SORT_FIELD = "sortField"
        const val KEY_SORT_ORDER = "sortOrder"
        const val KEY_FOLDER = "folderId"
    }
}
