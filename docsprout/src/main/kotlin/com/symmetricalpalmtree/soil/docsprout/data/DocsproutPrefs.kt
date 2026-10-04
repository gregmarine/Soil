package com.symmetricalpalmtree.soil.docsprout.data

import android.content.Context

/**
 * What Docsprout remembers on this device: the document last open. Device-local: never in a
 * file, never backed up.
 */
class DocsproutPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The document last open, or null. The library's id, never a path. */
    var lastDocumentId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    private companion object {
        const val FILE = "docsprout"
        const val KEY_LAST = "lastDocumentId"
    }
}
