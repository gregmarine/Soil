package com.symmetricalpalmtree.soil.crypto

import android.util.Log
import java.io.File

/** The SQLite sidecars of a database file, and the one rule for removing them. */
object Sidecars {

    private const val TAG = "Sidecars"

    /** SQLite sidecars that may sit next to a database file (delete/move them with it). */
    fun of(dbFile: File): List<File> =
        listOf("-wal", "-shm", "-journal").map { File(dbFile.path + it) }

    /** **A non-empty `-wal` is never deleted**: it holds writes the main file does not. Pure. */
    fun removable(walExists: Boolean, walLength: Long): Boolean = !walExists || walLength == 0L

    /** Remove [file]'s `-wal` and `-shm` when the WAL is empty or absent. Never throws. */
    fun sweep(file: File) {
        try {
            val wal = File(file.path + "-wal")
            val shm = File(file.path + "-shm")
            if (!removable(wal.exists(), if (wal.exists()) wal.length() else 0L)) return
            if (wal.exists()) wal.delete()
            if (shm.exists()) shm.delete()
        } catch (e: Exception) {
            Log.w(TAG, "sidecar sweep failed for ${file.name}", e)
        }
    }
}
