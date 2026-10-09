package com.symmetricalpalmtree.soil.sketchsprout

import android.content.Context
import com.symmetricalpalmtree.soil.sketchsprout.sketch.SketchToolState

/**
 * What Sketchsprout remembers on this device: the sketchbook last open, whether the chrome was
 * hidden, and the tools — the armed pen kind and each kind's shade and size. Device-local: never in a
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
            // A key not there reads −1, which is out of every range and lands on the default: an
            // install over a build without sizes or a marker opens on their defaults, its kind and
            // shades intact.
            SketchToolState.of(
                prefs.getString(KEY_KIND, null),
                SketchToolState.Kind.entries.associateWith { k ->
                    SketchToolState.Setting(prefs.getInt(shadeKey(k), -1), prefs.getInt(sizeKey(k), -1))
                },
            )
        }.getOrDefault(SketchToolState.DEFAULT)
        set(value) {
            val e = prefs.edit().putString(KEY_KIND, value.kind.name)
            for ((k, s) in value.settings) e.putInt(shadeKey(k), s.shade).putInt(sizeKey(k), s.size)
            e.apply()
        }

    var chromeHidden: Boolean
        get() = prefs.getBoolean(KEY_CHROME_HIDDEN, false)
        set(value) { prefs.edit().putBoolean(KEY_CHROME_HIDDEN, value).apply() }

    private companion object {
        const val FILE = "sketchsprout"
        const val KEY_LAST = "lastSketchbookId"
        const val KEY_CHROME_HIDDEN = "chromeHidden"
        const val KEY_KIND = "toolKind"

        /** `pencilShade`, `penShade`, `markerShade` — the first two as they always were. */
        fun shadeKey(k: SketchToolState.Kind): String = "${k.name.lowercase()}Shade"

        /** `pencilSize`, `penSize`, `markerSize`. */
        fun sizeKey(k: SketchToolState.Kind): String = "${k.name.lowercase()}Size"
    }
}
