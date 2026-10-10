package com.symmetricalpalmtree.soil.calsprout

import android.content.Context

/**
 * How the person left the calendar's chrome: whether its bars were hidden. Nothing of what was
 * written or where the calendar was open — that is in the store, under the key — so this is an
 * ordinary preference, device-local, never backed up.
 */
class CalsproutPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the bars were hidden: the fallback only, for when Soil cannot answer. The flag is
     *  Soil's, one for every paper screen (`SharedChrome`). */
    var chromeHidden: Boolean
        get() = prefs.getBoolean(KEY_CHROME_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_CHROME_HIDDEN, value).apply()

    private companion object {
        const val FILE = "calsprout"
        const val KEY_CHROME_HIDDEN = "chromeHidden"
    }
}
