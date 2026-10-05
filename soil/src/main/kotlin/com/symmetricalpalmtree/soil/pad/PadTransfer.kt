package com.symmetricalpalmtree.soil.pad

/**
 * Ink a notebook sent to the Scratch Pad, parked in memory, one at a time, and taken by the pad
 * as it shows. A copy; a parking never taken up is replaced by the next, and it is gone with
 * Soil's process. What the pad gives back does not come this way: the pad puts it on the
 * clipboard, which is stored.
 */
object PadTransfer {

    class Incoming(val bytes: ByteArray, val placement: Int)

    private var incoming: Incoming? = null

    @Synchronized
    fun parkIncoming(bytes: ByteArray, placement: Int) { incoming = Incoming(bytes, placement) }

    @Synchronized
    fun takeIncoming(): Incoming? = incoming.also { incoming = null }
}
