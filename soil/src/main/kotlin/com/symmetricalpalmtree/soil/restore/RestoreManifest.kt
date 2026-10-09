package com.symmetricalpalmtree.soil.restore

import com.symmetricalpalmtree.soil.backup.BackupPredicates
import com.symmetricalpalmtree.soil.crypto.RekeyNames
import com.symmetricalpalmtree.soil.data.SoilFiles

/**
 * What a restore takes out of a backup folder, decided from the listing alone: pure, and the only
 * thing that says what gets staged. A backup folder is an accretion, not a curated set, so the
 * rules are filename shapes: `soil.db` (its presence makes a folder a backup), `<id>.soil`, and
 * `<name>.db` where the stem is a store name. A writer's `<name>.old` standing where `<name>` is
 * absent is the last good copy a killed swap stranded, and is read as `<name>`. Left where they
 * lie: a writer's `.part`, an `.old` beside its own file, a rekey's `.rekey.tmp` or `.old.bak`,
 * any `-shm` or `-journal`, every directory, anything else.
 */
class RestoreManifest(val items: List<Item>) {

    val totalBytes: Long = items.sumOf { if (it.size < 0L) 0L else it.size }
    val itemCount: Int = items.count { it.kind == ItemKind.SOIL }
    val storeCount: Int = items.count { it.kind == ItemKind.STORE }
    val index: Item = items.first { it.kind == ItemKind.INDEX }
    val hasUnknownSizes: Boolean = items.any { it.size < 0L }

    companion object {
        const val INDEX_NAME = BackupPredicates.INDEX_NAME
        private const val INDEX_WAL_NAME = INDEX_NAME + BackupPredicates.WAL_SUFFIX
        private const val SOIL_SUFFIX = SoilFiles.ITEM_SUFFIX
        private const val SOIL_WAL_SUFFIX = SOIL_SUFFIX + BackupPredicates.WAL_SUFFIX
        private const val STORE_WAL_SUFFIX = SoilFiles.STORE_SUFFIX + BackupPredicates.WAL_SUFFIX
        private const val SHM_SUFFIX = "-shm"
        private const val JOURNAL_SUFFIX = "-journal"
        private val SOIL_STEM = Regex("[A-Za-z0-9_-]+")

        /** Where the items and the stores live, under the staging root and live. */
        const val GARDEN = "garden"

        /** A non-directory `soil.db` is in [entries], or a `soil.db.old` read in its place. */
        fun isBackup(entries: List<Listed>): Boolean = indexEntry(entries) != null

        /** The index's entry as a restore reads it, named `soil.db`; null when not a backup. */
        fun indexEntry(entries: List<Listed>): Listed? = resolved(entries).firstOrNull { !it.first.isDir && it.first.name == INDEX_NAME }?.first

        /** What a restore would take from [entries] on [leg], in staging order; null when not a backup. */
        fun plan(entries: List<Listed>, leg: RestoreLeg): RestoreManifest? {
            if (!isBackup(entries)) return null
            val taken = ArrayList<Item>(entries.size)
            for ((entry, sourceName) in resolved(entries)) {
                val kind = kindOf(entry) ?: continue
                taken.add(Item(entry.name, entry.size, kind, relativePathFor(entry.name, kind), sourceName))
            }
            val mainNames = taken.filter { !it.kind.isWal() }.mapTo(HashSet()) { it.name }
            val kept = taken.filter { item ->
                when {
                    !item.kind.isWal() -> true
                    leg == RestoreLeg.CLOUD -> false
                    else -> mainOf(item.name) in mainNames
                }
            }
            return RestoreManifest(ordered(kept))
        }

        /** Each entry as read, with the name to fetch: a `<name>.old` whose `<name>` is absent reads as `<name>`. */
        private fun resolved(entries: List<Listed>): List<Pair<Listed, String>> {
            val present = entries.filter { !it.isDir }.mapTo(HashSet()) { it.name }
            return entries.map { entry ->
                val main = entry.name.removeSuffix(BackupPredicates.OLD_SUFFIX)
                if (!entry.isDir && main != entry.name && main.isNotEmpty() && main !in present) entry.copy(name = main) to entry.name
                else entry to entry.name
            }
        }

        private fun kindOf(entry: Listed): ItemKind? {
            if (entry.isDir) return null
            val name = entry.name
            if (name.endsWith(BackupPredicates.PART_SUFFIX) || name.endsWith(BackupPredicates.OLD_SUFFIX) ||
                name.endsWith(RekeyNames.TMP_SUFFIX) || name.endsWith(RekeyNames.BAK_SUFFIX) ||
                name.endsWith(SHM_SUFFIX) || name.endsWith(JOURNAL_SUFFIX)
            ) return null
            if (name == INDEX_NAME) return ItemKind.INDEX
            if (name == INDEX_WAL_NAME) return ItemKind.INDEX_WAL
            if (name.endsWith(SOIL_WAL_SUFFIX)) return if (SOIL_STEM.matches(name.dropLast(SOIL_WAL_SUFFIX.length))) ItemKind.SOIL_WAL else null
            if (name.endsWith(SOIL_SUFFIX)) return if (SOIL_STEM.matches(name.dropLast(SOIL_SUFFIX.length))) ItemKind.SOIL else null
            if (name.endsWith(STORE_WAL_SUFFIX)) return if (SoilFiles.storeName(name.dropLast(BackupPredicates.WAL_SUFFIX.length)) != null) ItemKind.STORE_WAL else null
            if (SoilFiles.storeName(name) != null) return ItemKind.STORE
            return null
        }

        private fun relativePathFor(name: String, kind: ItemKind): String = when (kind) {
            ItemKind.INDEX, ItemKind.INDEX_WAL -> name
            else -> "$GARDEN/$name"
        }

        private fun ItemKind.isWal(): Boolean = this == ItemKind.INDEX_WAL || this == ItemKind.SOIL_WAL || this == ItemKind.STORE_WAL

        private fun mainOf(walName: String): String = walName.dropLast(BackupPredicates.WAL_SUFFIX.length)

        /** The index first, then each main file followed by its own WAL, items then stores by name. */
        private fun ordered(items: List<Item>): List<Item> {
            val wals = items.filter { it.kind.isWal() }.associateBy { mainOf(it.name) }
            val out = ArrayList<Item>(items.size)
            fun emit(main: Item) { out.add(main); wals[main.name]?.let { out.add(it) } }
            items.firstOrNull { it.kind == ItemKind.INDEX }?.let(::emit)
            items.filter { it.kind == ItemKind.SOIL }.sortedBy { it.name }.forEach(::emit)
            items.filter { it.kind == ItemKind.STORE }.sortedBy { it.name }.forEach(::emit)
            return out
        }
    }
}
