package com.symmetricalpalmtree.soil.paper.chrome

/**
 * **The chrome's hidden state is one flag, Soil's**, shared by every paper screen of every app —
 * the notebook, the sticky editor, the Scratch Pad, the sketchbook, the calendar — as Notesprout
 * SN's one host flag was (arc 33 / F3). The screens are five processes, so the flag lives in
 * Soil and crosses the seam (`ISoilSeam.chromeHidden` / `setChromeHidden`); each app keeps its
 * own copy only as the fallback for when Soil cannot answer.
 *
 * The rules, pure, so they are tested off the device. `PaperScreenActivity` is their one caller.
 */
object SharedChrome {

    /**
     * The state a screen's chrome is built in: Soil's shared flag when it has already answered,
     * else the screen's own state from before a rebuild, else the app's local fallback. The
     * shared flag is never older than either: every flip writes it.
     */
    fun opening(shared: Boolean?, saved: Boolean?, local: Boolean): Boolean = shared ?: saved ?: local

    /**
     * What to put the chrome into when Soil's answer arrives for a screen whose chrome is
     * [current]: the shared state when it differs, null when there is nothing to do — no answer
     * (Soil unreachable: the local state stands), or the screen is already so.
     */
    fun adopt(shared: Boolean?, current: Boolean): Boolean? = shared?.takeIf { it != current }
}
