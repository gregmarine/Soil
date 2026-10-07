package com.symmetricalpalmtree.soil.sketchsprout

import android.content.Context
import com.symmetricalpalmtree.soil.sketchsprout.sketch.SketchToolState

/**
 * What Sketchsprout remembers on this device: the sketchbook last open, whether the chrome was
 * hidden, and the tools — the armed pen kind and each kind's shade. Device-local: never in a
 * file, never backed up. **Never the eraser and never the smudge** (Greg, 2026-10-07): a face
 * that opened on the rubber would read as a broken pencil, so [tools] always names a pen kind.
 *
 * A stored value is untrusted input: a kind this build does not know reads as the pencil, a
 * shade it does not offer as that kind's default ([SketchToolState.of]).
 */
class SketchPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The sketchbook last open, or null. The library's id, never a path. */
    var lastSketchbookId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    /** The tools as last picked, or the defaults. */
    var tools: SketchToolState
        get() = runCatching {
            SketchToolState.of(prefs.getString(KEY_KIND, null), prefs.getInt(KEY_PENCIL_SHADE, -1), prefs.getInt(KEY_PEN_SHADE, -1))
        }.getOrDefault(SketchToolState.DEFAULT)
        set(value) {
            prefs.edit().putString(KEY_KIND, value.kind.name).putInt(KEY_PENCIL_SHADE, value.pencilShade).putInt(KEY_PEN_SHADE, value.penShade).apply()
        }

    var chromeHidden: Boolean
        get() = prefs.getBoolean(KEY_CHROME_HIDDEN, false)
        set(value) { prefs.edit().putBoolean(KEY_CHROME_HIDDEN, value).apply() }

    private companion object {
        const val FILE = "sketchsprout"
        const val KEY_LAST = "lastSketchbookId"
        const val KEY_CHROME_HIDDEN = "chromeHidden"
        const val KEY_KIND = "toolKind"
        const val KEY_PENCIL_SHADE = "pencilShade"
        const val KEY_PEN_SHADE = "penShade"
    }
}
