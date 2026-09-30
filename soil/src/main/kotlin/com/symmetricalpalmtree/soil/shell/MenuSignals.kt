package com.symmetricalpalmtree.soil.shell

/**
 * What the side menu tells the screens of Soil itself, in this process.
 *
 * A paper screen holds the e-ink panel for its ink, and a window drawn over it does not show
 * until the screen lets the panel go. The menu is drawn over whatever is in front, so when that
 * is Soil's own paper the menu says so first.
 */
object MenuSignals {

    /** Set by a paper screen while it is in front; run as the menu is about to show. */
    @Volatile
    var beforeMenuShows: (() -> Unit)? = null
}
