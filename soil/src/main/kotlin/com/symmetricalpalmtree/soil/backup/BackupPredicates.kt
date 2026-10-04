package com.symmetricalpalmtree.soil.backup

import com.symmetricalpalmtree.soil.data.index.ItemFlags

/** The pure half of the backup run: the needs-backup rule, the work-list math and the filename scheme. */
object BackupPredicates {

    /** The index's destination name. */
    const val INDEX_NAME = "soil.db"
    const val WAL_SUFFIX = "-wal"
    const val PART_SUFFIX = ".part"
    const val OLD_SUFFIX = ".old"

    /** The cloud tree's first segment: `Backups/<device folder>/` under the provider's root. */
    const val CLOUD_BACKUPS_FOLDER = "Backups"

    /** A debug build writes here inside the chosen tree: debug and release coexist on the Nomad. */
    const val DEV_SUBDIR = "dev"

    fun itemName(itemId: String): String = "$itemId.soil"

    fun isExcluded(flags: Int): Boolean = (flags and ItemFlags.EXCLUDE_FROM_BACKUP) != 0

    /** Copy when not excluded and either never stamped or edited since. Equal means backed up. */
    fun needsBackup(updatedAt: Long, stamp: Long?, excluded: Boolean): Boolean = !excluded && (stamp == null || updatedAt > stamp)

    data class Candidate(val id: String, val updatedAt: Long, val flags: Int = 0)

    data class WorkList(val toCopy: List<Candidate>, val excluded: Int, val upToDate: Int)

    fun workList(items: List<Candidate>, stamps: Map<String, Long>): WorkList {
        val toCopy = ArrayList<Candidate>()
        var excluded = 0
        var upToDate = 0
        for (n in items) {
            when {
                isExcluded(n.flags) -> excluded++
                needsBackup(n.updatedAt, stamps[n.id], excluded = false) -> toCopy.add(n)
                else -> upToDate++
            }
        }
        return WorkList(toCopy, excluded, upToDate)
    }

    /** The stamp map without entries for items that no longer exist; after a successful run only. */
    fun pruneStamps(stamps: Map<String, Long>, aliveIds: Set<String>): Map<String, Long> = stamps.filterKeys { it in aliveIds }
}
