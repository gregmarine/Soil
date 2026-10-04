package com.symmetricalpalmtree.soil.importing

import com.symmetricalpalmtree.soil.export.ExportStamp

/**
 * Where an imported item lands when it asks for its own folders: each remembered folder is
 * reused when it is a live folder here, created when nothing holds its id, and the walk stops
 * one level up at anything else. Create-only, never a move; depth capped. Pure.
 */
object AncestryPlan {

    const val MAX_DEPTH = 20

    enum class Slot { MISSING, LIVE_FOLDER, BLOCKED }

    data class Create(val id: String, val name: String, val parentId: String)

    /** [parentId] is `""` at the root. */
    data class Plan(val parentId: String, val create: List<Create>, val truncated: Boolean)

    fun plan(path: List<ExportStamp.Folder>, slotOf: (String) -> Slot): Plan {
        val create = ArrayList<Create>(path.size)
        var parent = ""
        var truncated = false
        for ((depth, ref) in path.withIndex()) {
            if (depth >= MAX_DEPTH) { truncated = true; break }
            val id = SafeImportId.orNull(ref.id)
            if (id == null) { truncated = true; break }
            when (slotOf(id)) {
                Slot.LIVE_FOLDER -> parent = id
                Slot.MISSING -> { create += Create(id, ImportNames.folderName(ref.name), parent); parent = id }
                Slot.BLOCKED -> { truncated = true; break }
            }
        }
        return Plan(parent, create, truncated)
    }
}
