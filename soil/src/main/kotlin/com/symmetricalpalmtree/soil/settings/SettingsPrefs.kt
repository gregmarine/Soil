package com.symmetricalpalmtree.soil.settings

import android.content.Context

/** What Settings remembers, device-wide and plaintext: ids and tags, never a name of the person's. */
class SettingsPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The chosen recogniser's component, `Recognizers.NONE` for none, or null for no choice yet. */
    var recognizerKey: String?
        get() = prefs.getString(KEY_RECOGNIZER, null)
        set(value) { prefs.edit().putString(KEY_RECOGNIZER, value).apply() }

    var recognizerLanguage: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    /** The exporter last used, by package, so the export screen opens on it. */
    var lastExporter: String?
        get() = prefs.getString(KEY_LAST_EXPORTER, null)
        set(value) { prefs.edit().putString(KEY_LAST_EXPORTER, value).apply() }

    /** The page size last chosen for an item that flows, one of `Seam.PAGE_*`. */
    var lastPageSize: String?
        get() = prefs.getString(KEY_LAST_PAGE_SIZE, null)
        set(value) { prefs.edit().putString(KEY_LAST_PAGE_SIZE, value).apply() }

    private companion object {
        const val FILE = "soil_settings"
        const val KEY_LAST_PAGE_SIZE = "lastPageSize"
        const val KEY_RECOGNIZER = "recognizer"
        const val KEY_LANGUAGE = "recognizerLanguage"
        const val KEY_LAST_EXPORTER = "lastExporter"
    }
}
