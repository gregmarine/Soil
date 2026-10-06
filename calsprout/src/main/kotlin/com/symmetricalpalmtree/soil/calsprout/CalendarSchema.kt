package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.ink.InkSql
import com.symmetricalpalmtree.soil.seam.SeamSchema

/**
 * The calendar's tables in its app store, declared once and applied by Soil when the store is
 * opened (`ISoilSeam.openAppStore`). Every statement is checked by the seam at construction, so
 * a mistake here fails on this side, at class-load, and never at the open.
 *
 * ```sql
 * calendar (id, name, createdAt)                                          -- one row for now
 * period   (id, calendarId → calendar.id ON DELETE CASCADE, kind, date)   -- UNIQUE(calendarId, kind, date)
 * page     (id, periodId → period.id ON DELETE CASCADE, half, width, height, createdAt, updatedAt)
 * stroke   (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
 * state    (calendarId → calendar.id ON DELETE CASCADE, key, value)       -- PK(calendarId, key)
 * ```
 *
 * **The tables never assume there is one calendar** (`design.md` §3, `BACKLOG.md`): every row
 * that belongs to a calendar names it, and the one calendar there is today is a row like any
 * later one would be, so a second calendar is a row, not a rewrite. [CalendarStore] is built for
 * one calendar id and asks about nothing else.
 *
 * A `period` is a month (dated by its first day), a week (by its Sunday) or a day; the kind
 * column says which. A month or a week owns one `page` (`half` 0); a day owns two (0 = AM,
 * 1 = PM). **Rows are minted on the first stroke, never on open** — browsing empty months writes
 * nothing. `stroke` is `:paper`'s row ([InkSql]), the Scratch Pad's exactly; `page.width/height`
 * is the page's minted size, this device's screen, so a grid rendered at the page's own size
 * keeps grid and ink registered on any screen the store is later carried to.
 *
 * Foreign keys are ON for the connection, so a delete cascades — which is why neither `period`
 * nor `page` is ever written with `INSERT OR REPLACE` (REPLACE deletes the conflicting row first,
 * and that delete cascades). Nothing deletes a `period`.
 *
 * **A landed step is never edited.** A change is a new step on the end.
 */
object CalendarSchema {

    const val KIND = "calsprout"

    /** The one calendar's id, minted on the first open. A later version mints more. */
    const val DEFAULT_CALENDAR = "default"

    val PAGES_STEP: List<String> = listOf(
        """CREATE TABLE calendar (
               id TEXT PRIMARY KEY,
               name TEXT NOT NULL,
               createdAt INTEGER NOT NULL)""",
        """CREATE TABLE period (
               id TEXT PRIMARY KEY,
               calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
               kind INTEGER NOT NULL,
               date TEXT NOT NULL,
               UNIQUE(calendarId, kind, date))""",
        """CREATE TABLE page (
               id TEXT PRIMARY KEY,
               periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE,
               half INTEGER NOT NULL,
               width REAL NOT NULL,
               height REAL NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               UNIQUE(periodId, half))""",
        InkSql.CREATE_STROKE_TABLE,
        InkSql.CREATE_STROKE_INDEX,
        """CREATE TABLE state (
               calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
               key TEXT NOT NULL,
               value TEXT NOT NULL,
               PRIMARY KEY(calendarId, key))""",
    )

    val SCHEMA = SeamSchema(kind = KIND, steps = listOf(PAGES_STEP))
}
