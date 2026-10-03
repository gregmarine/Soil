package com.symmetricalpalmtree.soil.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * **Whether a paper screen is in front**: one of Soil's own, or a Sprout app's with its client
 * attached. The shell follows it: while paper is in front its system-wide key filter is off,
 * because that filter is what let a resting palm cut the pen's stream (2026-10-03, five pages
 * written on the Nomad: with the filter, a palm landing at the edge strip while the pen was
 * down reached Android and cancelled the pen; without it, the firmware's palm rejection held).
 * The bar keys reach the shell from the paper screen's own window instead.
 */
object PaperFront {

    private val own = MutableStateFlow(false)
    private val app = MutableStateFlow(false)
    private val _inFront = MutableStateFlow(false)

    /** True while any paper screen is in front. */
    val inFront: StateFlow<Boolean> get() = _inFront

    /** Soil's own paper screen (the Scratch Pad) has come to the front, or left it. */
    fun ownPaper(inFront: Boolean) { own.value = inFront; combine() }

    /** A Sprout app's paper has attached its client, or detached it. */
    fun appPaper(inFront: Boolean) { app.value = inFront; combine() }

    private fun combine() { _inFront.value = own.value || app.value }
}
