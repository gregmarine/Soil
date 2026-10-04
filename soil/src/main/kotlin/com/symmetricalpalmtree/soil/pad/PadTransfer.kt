package com.symmetricalpalmtree.soil.pad

/**
 * Ink parked between a notebook and the Scratch Pad, in memory, one each way at a time: what a
 * notebook sent to the pad (taken by the pad as it shows) and what the pad sent back (taken by the
 * notebook as it comes to the front). Both are copies; a parking never taken up is replaced by the
 * next, and all of it is gone with Soil's process.
 */
object PadTransfer {

    class Incoming(val bytes: ByteArray, val placement: Int)

    private var incoming: Incoming? = null
    private var outgoing: ByteArray? = null

    @Synchronized
    fun parkIncoming(bytes: ByteArray, placement: Int) { incoming = Incoming(bytes, placement) }

    @Synchronized
    fun takeIncoming(): Incoming? = incoming.also { incoming = null }

    @Synchronized
    fun parkOutgoing(bytes: ByteArray) { outgoing = bytes }

    @Synchronized
    fun takeOutgoing(): ByteArray? = outgoing.also { outgoing = null }
}
