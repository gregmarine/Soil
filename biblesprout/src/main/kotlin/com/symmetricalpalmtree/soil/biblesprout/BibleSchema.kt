package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.seam.SeamSchema

/**
 * The reader's tables in its app store, declared once and applied by Soil when the store is
 * opened (`ISoilSeam.openAppStore`). Every statement is checked by the seam at construction, so
 * a mistake here fails on this side, at class-load, and never at the open.
 *
 * ```sql
 * state      (key TEXT PRIMARY KEY, value TEXT NOT NULL)                               -- v1
 * recent     (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL,
 *             PRIMARY KEY (usfm, chapter))                                             -- v2
 * recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)                               -- v3
 * ```
 *
 * `state` is one key/value table holding the last-read position. `recent` is one row per chapter
 * picked from the Contents or the Recents, stamped with when; the chapter is the key, so a
 * re-pick re-stamps rather than duplicates. `recent_ref` is the same for a passage, by its wire.
 * `INSERT OR REPLACE` is safe on all three: none has children for a row's replacement to cascade
 * away. Notesprout SN's three steps, as they landed there, minus its notes index: what links into
 * a passage is Soil's link index now.
 *
 * **A landed step is never edited.** A change is a new step on the end.
 */
object BibleSchema {

    const val KIND = "biblesprout"

    val STATE_STEP: List<String> = listOf(
        "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    )

    val RECENT_STEP: List<String> = listOf(
        "CREATE TABLE recent (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL, " +
            "PRIMARY KEY (usfm, chapter))",
    )

    val RECENT_REF_STEP: List<String> = listOf(
        "CREATE TABLE recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)",
    )

    val SCHEMA = SeamSchema(kind = KIND, steps = listOf(STATE_STEP, RECENT_STEP, RECENT_REF_STEP))
}
