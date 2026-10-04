package com.symmetricalpalmtree.soil.ext.cloud

/**
 * Folder ids by path under the provider's root (arc 34 / M9b) — the root is the empty path. A
 * `DriveApi` is built per Binder call, so the memory that makes a backup run's second upload cost
 * no folder listing at all lives here, process-wide ([DriveFolders.cache] — `DriveTokens.cache`'s
 * shape); the root's id is also persisted in the store, as it always was.
 *
 * **The one rule** (`docs/cloud.md` § Paths — the root's rule, now every folder's): an id is
 * trusted until Drive answers a 404 under it, and then [evict] drops the whole path — every prefix
 * (the root included: which segment went stale is not knowable from the 404) and every descendant
 * — and the caller re-resolves once. A stale sibling subtree keeps its ids and earns its own 404.
 * Never probed up front: a metadata read per call was the old root's cost, and the finding.
 *
 * Keys are segment lists, not joined strings: a name may carry any character.
 */
class FolderCache {

    private val ids = HashMap<List<String>, String>()

    @Synchronized
    fun get(path: List<String>): String? = ids[path]

    @Synchronized
    fun put(path: List<String>, id: String) {
        ids[path.toList()] = id
    }

    /** Drop [path], every prefix of it (the root included) and everything under it. */
    @Synchronized
    fun evict(path: List<String>) {
        ids.keys.removeAll { key ->
            key.size <= path.size && key == path.subList(0, key.size) ||
                key.size > path.size && key.subList(0, path.size) == path
        }
    }

    @Synchronized
    fun clear() = ids.clear()
}

/** The process-wide cache (`DriveTokens`' shape). */
object DriveFolders {
    val cache: FolderCache = FolderCache()
}
