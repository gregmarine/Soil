package com.symmetricalpalmtree.soil.restore

/** What a source's listing means, pure: the device folders under `Backups/`, and one chooser row per folder that is a backup. */
object RestoreRows {

    fun deviceFolders(backupsListing: List<Listed>): List<Listed> = backupsListing.filter { it.isDir }.sortedBy { it.name }

    /** Null when the folder is not a backup: skipped, never an error. [handle] is what the source re-lists by at fetch time. */
    fun rowFor(name: String, entries: List<Listed>, leg: RestoreLeg, handle: String): RestoreBackup? {
        val manifest = RestoreManifest.plan(entries, leg) ?: return null
        val index = entries.first { !it.isDir && it.name == RestoreManifest.INDEX_NAME }
        return RestoreBackup(name = name, itemCount = manifest.itemCount, indexModifiedAt = index.modifiedAt, totalBytes = manifest.totalBytes, handle = handle)
    }
}
