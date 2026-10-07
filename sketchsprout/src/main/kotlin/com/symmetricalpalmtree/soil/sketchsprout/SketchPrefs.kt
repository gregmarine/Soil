package com.symmetricalpalmtree.soil.sketchsprout

import android.content.Context

/**
 * What Sketchsprout remembers on this device: the sketchbook last open and whether the chrome
 * was hidden. Device-local: never in a file, never backed up. The tools and their shades join in
 * phase 2, on the same rule.
 */
class SketchPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The sketchbook last open, or null. The library's id, never a path. */
    var lastSketchbookId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    var chromeHidden: Boolean
        get() = prefs.getBoolean(KEY_CHROME_HIDDEN, false)
        set(value) { prefs.edit().putBoolean(KEY_CHROME_HIDDEN, value).apply() }

    private companion object {
        const val FILE = "sketchsprout"
        const val KEY_LAST = "lastSketchbookId"
        const val KEY_CHROME_HIDDEN = "chromeHidden"
    }
}
