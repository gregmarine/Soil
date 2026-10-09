package com.symmetricalpalmtree.soil.cloud

import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.ExtensionCallFailed

/**
 * Soil's own checks on what it is about to send across the cloud point, and on what comes back,
 * run before the bind: a refusal must never start a process. Pure, so the table is tested on
 * the JVM. A failure is an [ExtensionCallFailed] rather than an argument error because a file
 * name comes from the person's own naming, and "that name cannot go to the cloud" is a thing to
 * say, not to crash on.
 */
object CloudArgs {

    fun requirePath(path: Array<String>) {
        if (path.size > CloudContract.MAX_PATH_DEPTH) throw ExtensionCallFailed("path has ${path.size} segments; at most ${CloudContract.MAX_PATH_DEPTH}")
        for ((i, segment) in path.withIndex()) if (!CloudContract.isName(segment)) throw ExtensionCallFailed("path segment $i is not a name")
    }

    fun requireName(name: String) {
        if (!CloudContract.isName(name)) throw ExtensionCallFailed("not a legal name (${name.length} chars)")
    }

    fun requireMime(mime: String) {
        if (!CloudContract.isMime(mime)) throw ExtensionCallFailed("not a MIME type (${mime.length} chars)")
    }

    fun requireEntryId(id: String) {
        if (!CloudContract.isEntryId(id)) throw ExtensionCallFailed("not an entry id (${id.length} chars)")
    }

    /** Zero is a file: an export of an empty page is not an error. */
    fun requireExpectedBytes(bytes: Long) {
        if (bytes < 0) throw ExtensionCallFailed("expectedBytes is negative ($bytes)")
    }

    /** A listing: present, and no longer than the contract allows (a provider truncates, never overflows). */
    fun checkList(entries: Array<CloudEntry>?): List<CloudEntry> {
        if (entries == null) throw ExtensionCallFailed("list returned nothing")
        if (entries.size > CloudContract.MAX_LIST_ENTRIES) throw ExtensionCallFailed("list returned ${entries.size} entries; at most ${CloudContract.MAX_LIST_ENTRIES}")
        return entries.asList()
    }

    /** A listing that reached the contract's cap may have been truncated by the provider: what it leaves out is unknown. */
    fun mayBeTruncated(entries: List<CloudEntry>): Boolean = entries.size >= CloudContract.MAX_LIST_ENTRIES

    fun checkFolder(entry: CloudEntry?): CloudEntry {
        if (entry == null) throw ExtensionCallFailed("ensureFolder returned nothing")
        if (!entry.isFolder) throw ExtensionCallFailed("ensureFolder returned a file")
        return entry
    }

    /** The size is deliberately not checked here: a provider's metadata can lag its own write. */
    fun checkUploaded(entry: CloudEntry?): CloudEntry {
        if (entry == null) throw ExtensionCallFailed("upload returned nothing")
        if (entry.isFolder) throw ExtensionCallFailed("upload returned a folder")
        return entry
    }

    fun checkDownloaded(bytes: Long): Long {
        if (bytes < 0) throw ExtensionCallFailed("download reported $bytes bytes")
        return bytes
    }
}
