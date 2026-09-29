package com.symmetricalpalmtree.soil.shell

/**
 * **Taking the home screen back after boot.** Pure.
 *
 * About fifteen seconds after boot the firmware runs a routine of its own: its unlock screen, and
 * then the app it remembers as last used — its Notes app, unless another of its own was — on top
 * of whatever the home screen is. It cannot be told to remember Soil. So within the boot window,
 * the firmware's Notes arriving in front **without the person having asked for it** is that push,
 * and Soil puts its home screen back.
 *
 * Once the person has opened Notes themselves, or the window has passed, Notes in front is what
 * they wanted and is left alone.
 */
object BootTakeBack {

    const val FIRMWARE_NOTES = "com.ratta.supernote.note"

    /** How long after boot a push is still expected. */
    const val WINDOW_MS = 180_000L

    /** Set when the person opens the firmware's Notes through Soil. Never cleared: from then on
     *  this boot, Notes is theirs. */
    @Volatile
    var personAskedForNotes: Boolean = false

    fun isPush(front: String, personAsked: Boolean, sinceBootMs: Long): Boolean =
        front == FIRMWARE_NOTES && !personAsked && sinceBootMs in 0 until WINDOW_MS
}
