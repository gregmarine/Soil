package com.symmetricalpalmtree.soil.pad

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How the person left the pad: whether its bars were hidden. Nothing of what was written — that
 * is in the encrypted store — so this is an ordinary preference.
 *
 * Read from disk once, off the main thread, as the app starts; after that it is answered from
 * memory, so the screen never waits on storage to draw its first frame.
 */
object PadPrefs {

    private const val FILE = "soil_pad"
    private const val KEY_CHROME_HIDDEN = "chromeHidden"

    @Volatile
    var chromeHidden: Boolean = false
        private set

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        chromeHidden = prefs(context).getBoolean(KEY_CHROME_HIDDEN, false)
    }

    fun setChromeHidden(context: Context, hidden: Boolean) {
        chromeHidden = hidden
        prefs(context).edit().putBoolean(KEY_CHROME_HIDDEN, hidden).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
