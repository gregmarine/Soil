package com.symmetricalpalmtree.soil.notesprout.data

import android.content.Context
import com.symmetricalpalmtree.soil.paper.core.InkTones

/**
 * What Notesprout remembers on this device: the notebook last open, the pen's shade, and
 * whether the chrome was hidden. Device-local: never in a file, never backed up.
 *
 * A stored value is untrusted input: a shade this build does not offer reads as black.
 */
class NotebookPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The notebook last open, or null. The library's id, never a path. */
    var lastNotebookId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    /** The pen's shade as a level on [InkTones]' ladder. One shade, device-wide: a grey pen is a
     *  way of working, not a property of a page. */
    var penLevel: Int
        get() = InkTones.levelOrElse(runCatching { prefs.getInt(KEY_LEVEL, InkTones.BLACK) }.getOrDefault(InkTones.BLACK))
        set(value) { prefs.edit().putInt(KEY_LEVEL, InkTones.levelOrElse(value)).apply() }

    var chromeHidden: Boolean
        get() = prefs.getBoolean(KEY_CHROME_HIDDEN, false)
        set(value) { prefs.edit().putBoolean(KEY_CHROME_HIDDEN, value).apply() }

    private companion object {
        const val FILE = "notesprout"
        const val KEY_LAST = "lastNotebookId"
        const val KEY_LEVEL = "penLevel"
        const val KEY_CHROME_HIDDEN = "chromeHidden"
    }
}
