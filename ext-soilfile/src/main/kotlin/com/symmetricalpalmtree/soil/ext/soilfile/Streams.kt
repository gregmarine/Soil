package com.symmetricalpalmtree.soil.ext.soilfile

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.IOException

/** A verbatim copy from one descriptor to the other, synced when the destination is a regular
 *  file, and checked against the source's size when the descriptor will say it. */
internal object Streams {

    private const val BUFFER_BYTES = 64 * 1024

    fun copy(src: ParcelFileDescriptor, dst: ParcelFileDescriptor, tag: String, what: String): Long {
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(src).use { input ->
            val expected = src.statSize.takeIf { it >= 0L } ?: runCatching { input.channel.size() }.getOrNull()?.takeIf { it > 0L }
            ParcelFileDescriptor.AutoCloseOutputStream(dst).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    total += n
                }
                output.flush()
                Sync.ifRegular(output.fd, tag, what)
            }
            if (expected != null && total != expected) throw IllegalStateException("short $what: $total of $expected bytes")
        }
        return total
    }
}
