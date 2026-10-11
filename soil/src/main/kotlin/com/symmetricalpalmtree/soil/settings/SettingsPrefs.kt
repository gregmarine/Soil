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

    /** Whether the last export went to the cloud; the screen opens on it while the account is connected. */
    var lastDestinationCloud: Boolean
        get() = prefs.getBoolean(KEY_LAST_DESTINATION_CLOUD, false)
        set(value) { prefs.edit().putBoolean(KEY_LAST_DESTINATION_CLOUD, value).apply() }

    /** The cloud folder last exported to for a kind of item, as `ExportDestination.encodeFolder` spells it; null for none yet. */
    fun lastCloudFolder(kind: String): String? = prefs.getString(KEY_LAST_CLOUD_FOLDER + kind, null)

    fun setLastCloudFolder(kind: String, encoded: String) { prefs.edit().putString(KEY_LAST_CLOUD_FOLDER + kind, encoded).apply() }

    /** The folder on this device a kind of export last went to, as `LocalFiles.encodePath` writes it. */
    fun lastLocalFolder(kind: String): String? = prefs.getString(KEY_LAST_LOCAL_FOLDER + kind, null)

    fun setLastLocalFolder(kind: String, encoded: String) { prefs.edit().putString(KEY_LAST_LOCAL_FOLDER + kind, encoded).apply() }

    /** The person chose Android's picker over switching All files access on; asked no more until the Settings row is tapped. */
    var localPickerDeclined: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_PICKER_DECLINED, false)
        set(value) { prefs.edit().putBoolean(KEY_LOCAL_PICKER_DECLINED, value).apply() }

    private companion object {
        const val FILE = "soil_settings"
        const val KEY_LAST_PAGE_SIZE = "lastPageSize"
        const val KEY_RECOGNIZER = "recognizer"
        const val KEY_LANGUAGE = "recognizerLanguage"
        const val KEY_LAST_EXPORTER = "lastExporter"
        const val KEY_LAST_DESTINATION_CLOUD = "lastDestinationCloud"
        const val KEY_LAST_CLOUD_FOLDER = "lastCloudFolder:"
        const val KEY_LAST_LOCAL_FOLDER = "lastLocalFolder:"
        const val KEY_LOCAL_PICKER_DECLINED = "localPickerDeclined"
    }
}
