package com.symmetricalpalmtree.soil.ext.soilfile

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException

/** `fsync` only a regular file: a pipe or a provider's socket refuses it. */
internal object Sync {
    fun ifRegular(fd: FileDescriptor, tag: String, what: String) {
        val regular = try {
            OsConstants.S_ISREG(Os.fstat(fd).st_mode)
        } catch (e: Exception) {
            Log.w(tag, "destination could not be stat'd: ${e.javaClass.simpleName}")
            false
        }
        if (regular) {
            try {
                fd.sync()
            } catch (e: IOException) {
                throw IllegalStateException("syncing the $what failed (${e.javaClass.simpleName})")
            }
        }
    }
}
