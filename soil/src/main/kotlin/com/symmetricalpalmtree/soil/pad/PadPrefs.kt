package com.symmetricalpalmtree.soil.pad

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **The chrome's hidden state, one flag for every paper screen**: the pad's, and the Sprout apps'
 * through the seam (`ISoilSeam.chromeHidden` / `setChromeHidden`), as Notesprout SN's one host
 * flag was. It began as the pad's own and kept its file and key, so the state the pad was left in
 * is the one every screen starts from. Nothing of what was written — that is in the encrypted
 * store — so this is an ordinary preference.
 *
 * Read from disk once, off the main thread, as the app starts; after that the pad is answered
 * from memory, so the screen never waits on storage to draw its first frame. The seam reads it
 * on its binder thread, from the preference itself, which is there however Soil was started.
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

    /** The flag as stored. Off the main thread: the seam's binder thread, or IO. */
    fun readChromeHidden(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHROME_HIDDEN, false).also { chromeHidden = it }

    fun setChromeHidden(context: Context, hidden: Boolean) {
        chromeHidden = hidden
        prefs(context).edit().putBoolean(KEY_CHROME_HIDDEN, hidden).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
