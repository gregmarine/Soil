package com.symmetricalpalmtree.soil.ext.image

import java.io.OutputStream

/** Counts what passes through: the measured byte count an export reports. */
internal class CountingOutputStream(private val out: OutputStream) : OutputStream() {

    var count: Long = 0L
        private set

    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    override fun flush() = out.flush()
    override fun close() = out.close()
}
