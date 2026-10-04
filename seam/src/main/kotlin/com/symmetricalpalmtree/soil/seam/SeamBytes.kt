package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory
import android.system.OsConstants

/**
 * **A payload in one piece**: the bytes live in [memory]`[0 until byteCount]`, a region of shared
 * memory, so nothing is cut to fit a Binder transaction.
 *
 * The same handshake in both directions. The **sender** makes the region, writes, makes it
 * read-only, hands it over, and closes its own handle once the call that carries it has returned.
 * The **receiver** maps it read-only, copies the bytes out, and closes it. [SeamShared] is that
 * handshake, written once.
 *
 * Wire form: `SharedMemory · int byteCount`. An empty payload rides a region of one byte with
 * `byteCount` 0, because a region of no bytes cannot be made.
 */
class SeamBytes(val memory: SharedMemory, val byteCount: Int) : Parcelable {

    init {
        requireValid(byteCount, memory.size)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(memory, flags)
        dest.writeInt(byteCount)
    }

    /** It carries a file descriptor, and must say so. */
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    companion object {
        /** The constructor's checks, pure so they are JVM-tested. */
        fun requireValid(byteCount: Int, memorySize: Int) {
            require(byteCount in 0..SeamLimits.MAX_PAYLOAD_BYTES) {
                "byteCount must be 0..${SeamLimits.MAX_PAYLOAD_BYTES} ($byteCount)"
            }
            require(byteCount <= memorySize) { "byteCount $byteCount exceeds the region ($memorySize)" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<SeamBytes> = object : Parcelable.Creator<SeamBytes> {
            @Suppress("DEPRECATION")
            override fun createFromParcel(parcel: Parcel): SeamBytes = SeamBytes(
                memory = requireNotNull(parcel.readParcelable(SharedMemory::class.java.classLoader)) { "no region" },
                byteCount = parcel.readInt(),
            )

            override fun newArray(size: Int): Array<SeamBytes?> = arrayOfNulls(size)
        }
    }
}

/**
 * The shared-memory handshake behind [SeamBytes].
 *
 * Every step can throw `ErrnoException`, which is **outside the set Binder carries**. Inside a
 * stub the failure must be turned into an `IllegalStateException`, or the call dies in silence
 * and the caller reads an empty reply as success.
 */
object SeamShared {

    /** [bytes] in a fresh read-only region. The sender closes it once the call has returned. */
    fun write(bytes: ByteArray): SeamBytes {
        SeamBytes.requireValid(bytes.size, maxOf(1, bytes.size))
        val region = SharedMemory.create("seam", maxOf(1, bytes.size))
        try {
            val buffer = region.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            region.setProtect(OsConstants.PROT_READ)
        } catch (t: Throwable) {
            region.close()
            throw t
        }
        return SeamBytes(region, bytes.size)
    }

    /** The bytes of [value], and the region closed whatever happens. */
    fun readAndClose(value: SeamBytes): ByteArray = try {
        val buffer = value.memory.mapReadOnly()
        try {
            ByteArray(value.byteCount).also { buffer.get(it) }
        } finally {
            SharedMemory.unmap(buffer)
        }
    } finally {
        value.memory.close()
    }
}
