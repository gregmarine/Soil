package com.symmetricalpalmtree.soil.bootstrap

import com.symmetricalpalmtree.soil.data.index.SoilIndex

/**
 * **Whether a screen that needs the key may open, and where to go when it may not** — the pure
 * decision the Scratch Pad, the Encryption screen and the home screen all share.
 *
 * The app grid and the side menu never ask: they need no key, which is what keeps the device
 * usable while the library is locked or still being prepared.
 *
 *  1. The index is not open → there is nothing to decide but why.
 *  2. The recovery key was never acknowledged → show it ([Route.RECOVERY_KEY]). Nothing is
 *     written under a key that has not been saved. A minted rotation clears the acknowledgement
 *     at commit, so this is also how the NEW key is shown once.
 *  3. A rotation marker exists → the Encryption screen ([Route.RESUME_ROTATION]), whose banner is
 *     the resume: the library is in two keys until it finishes.
 *  4. Otherwise the screen opens.
 *
 * The order of 2 and 3 matters only when both are set — a commit that died between clearing the
 * acknowledgement and clearing the marker — and then the key shown IS the marker's, so showing it
 * first is right.
 */
object KeyGate {

    enum class Route {
        OPEN,
        /** The index is still being opened; on a first launch, for several seconds. */
        PREPARING,
        UNLOCK,
        RECOVERY_KEY,
        RESUME_ROTATION,
        /** The index file is foreign, damaged or out of reach. No key would help. */
        BLOCKED,
    }

    fun route(index: SoilIndex.State, acknowledged: Boolean, rotating: Boolean): Route = when (index) {
        SoilIndex.State.PREPARING -> Route.PREPARING
        SoilIndex.State.NEEDS_UNLOCK -> Route.UNLOCK
        SoilIndex.State.FOREIGN_FILE,
        SoilIndex.State.DAMAGED_FILE,
        SoilIndex.State.UNAVAILABLE -> Route.BLOCKED
        SoilIndex.State.READY -> when {
            !acknowledged -> Route.RECOVERY_KEY
            rotating -> Route.RESUME_ROTATION
            else -> Route.OPEN
        }
    }
}
