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
 * -- step 2 (events)
 * event           (id, calendarId → calendar.id ON DELETE CASCADE, type, title, startDate, endDate,
 *                  allDay, startMinute, endMinute, recurring, freq, interval, monthlyMode, endMode,
 *                  untilDate, endCount, noteText, noteWidth, noteHeight, createdAt, updatedAt)
 * event_weekday   (eventId → event.id ON DELETE CASCADE, weekday)      -- PK(eventId, weekday)
 * event_exception (eventId → event.id ON DELETE CASCADE, date)         -- PK(eventId, date)
 * event_reminder  (eventId → event.id ON DELETE CASCADE, amount, unit) -- PK(eventId, amount, unit)
 * note_stroke     (id, eventId → event.id ON DELETE CASCADE, "order", color, width, style, blob)
 * ```
 *
 * **Step 2 — events.** Columnar, no JSON, hard delete. An `event` is one row: its dates are ISO
 * `yyyy-MM-dd` text (which orders correctly as text, so a span overlap is
 * `startDate <= ? AND endDate >= ?`), its minutes are minute-of-day integers or NULL, and
 * `recurring` is a stored mirror of `freq IS NOT NULL` so the expansion read is an index hit.
 * `interval` / `monthlyMode` / `endMode` are NOT NULL on every row — a one-off carries the
 * defaults. The three small child tables are sets keyed by their whole row: a WEEKLY rule's
 * weekdays (ISO 1 = Mon … 7 = Sun), the occurrence STARTS removed from a series, and the
 * reminders (`amount` × `unit` DAYS | WEEKS). `note_stroke` is the pad's stroke row under its
 * own name and parent — the event's one page of handwriting, whose minted size is
 * `event.noteWidth/noteHeight` (`0 × 0` until the first stroke). `event` is never written with
 * `INSERT OR REPLACE` either: the cascade would take the note.
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

    val EVENTS_STEP: List<String> = listOf(
        """CREATE TABLE event (
               id TEXT PRIMARY KEY,
               calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE,
               type TEXT NOT NULL,
               title TEXT NOT NULL,
               startDate TEXT NOT NULL,
               endDate TEXT NOT NULL,
               allDay INTEGER NOT NULL,
               startMinute INTEGER,
               endMinute INTEGER,
               recurring INTEGER NOT NULL,
               freq TEXT,
               interval INTEGER NOT NULL,
               monthlyMode TEXT NOT NULL,
               endMode TEXT NOT NULL,
               untilDate TEXT,
               endCount INTEGER,
               noteText TEXT NOT NULL,
               noteWidth REAL NOT NULL,
               noteHeight REAL NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL)""",
        "CREATE INDEX event_span ON event(calendarId, startDate, endDate)",
        "CREATE INDEX event_recurring ON event(calendarId, recurring)",
        """CREATE TABLE event_weekday (
               eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
               weekday INTEGER NOT NULL,
               PRIMARY KEY(eventId, weekday))""",
        """CREATE TABLE event_exception (
               eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
               date TEXT NOT NULL,
               PRIMARY KEY(eventId, date))""",
        """CREATE TABLE event_reminder (
               eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
               amount INTEGER NOT NULL,
               unit TEXT NOT NULL,
               PRIMARY KEY(eventId, amount, unit))""",
        """CREATE TABLE note_stroke (
               id TEXT PRIMARY KEY,
               eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
               "order" INTEGER NOT NULL,
               color INTEGER NOT NULL,
               width REAL NOT NULL,
               style TEXT NOT NULL,
               blob BLOB NOT NULL)""",
        """CREATE INDEX note_stroke_event_order ON note_stroke(eventId, "order")""",
    )

    val SCHEMA = SeamSchema(kind = KIND, steps = listOf(PAGES_STEP, EVENTS_STEP))
}
