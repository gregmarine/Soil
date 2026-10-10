package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.InkSql
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * Every statement the calendar sends, as a pure builder — SQL text and bound arguments and
 * nothing else, so the shapes are JVM-testable without a store, pinned by `CalendarSqlTest`.
 *
 * **Every write is idempotent**, because a batch that failed is retried by whatever caller owns
 * it and the retry has to converge:
 *
 * - `calendar`, `period` and `page` rows are created with `INSERT OR IGNORE` — **never `INSERT OR
 *   REPLACE`**: REPLACE deletes the conflicting row first, and with `foreign_keys` ON that delete
 *   CASCADES. The page's `periodId` is resolved **inside the statement** from
 *   `(calendarId, kind, date)`, so a period that already exists (the day's other half minted it)
 *   is joined, not duplicated, whatever id the caller minted for it;
 * - a stroke row is written with `INSERT OR REPLACE` (a stroke has no children, so REPLACE is
 *   safe) and removed with `DELETE … WHERE id = ?` (a row that is not there is not an error);
 * - `state` rows are `INSERT OR REPLACE` — `state` has no children either.
 *
 * **The `stroke` half is `:paper`'s** ([InkSql]): the two write statements arrive as
 * [InkDocument.StrokeSql] by delegation and the read forwards.
 *
 * `"order"` is quoted (a real keyword); `key` and `value` are SQLite fallback keywords and pass
 * **unquoted** on the JVM and on the Nomad alike. `now` is passed in rather than read here so a
 * test can pin it.
 */
object CalendarSql : InkDocument.StrokeSql by InkSql {

    // ── calendar ──────

    fun insertCalendar(id: String, name: String, now: Long): Statement =
        Statement("INSERT OR IGNORE INTO calendar (id, name, createdAt) VALUES (?, ?, ?)", id, name, now)

    // ── period / page — minted on the first stroke ──────

    fun insertPeriod(id: String, calendarId: String, kind: Int, date: String): Statement =
        Statement("INSERT OR IGNORE INTO period (id, calendarId, kind, date) VALUES (?, ?, ?, ?)", id, calendarId, kind.toLong(), date)

    /** The page row under the period named by `(calendarId, kind, date)` — resolved in the
     *  statement, so the period row's id need not be the one this caller minted. */
    fun insertPage(id: String, calendarId: String, kind: Int, date: String, half: Int, width: Float, height: Float, now: Long): Statement =
        Statement(
            "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) " +
                "VALUES (?, (SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?), ?, ?, ?, ?, ?)",
            id, calendarId, kind.toLong(), date, half.toLong(), width.toDouble(), height.toDouble(), now, now,
        )

    /** The page learned its size (a `0 × 0` page — one a paste minted before any screen saw it). */
    fun sizePage(id: String, width: Float, height: Float, now: Long): Statement =
        Statement(
            "UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?",
            width.toDouble(), height.toDouble(), now, id,
        )

    /** The page's ink changed. */
    fun touchPage(id: String, now: Long): Statement =
        Statement("UPDATE page SET updatedAt = ? WHERE id = ?", now, id)

    // ── a page this showing minted — named by (period, half), never by the id it minted ──────

    /** The page row under `(calendarId, kind, date)` at [half], as a subselect: the row's own id,
     *  whichever screen minted it. Two screens (a `cal:` link starts a second) can each mint an
     *  id for one empty page; `UNIQUE(periodId, half)` keeps the first, and the other's strokes
     *  must land under it rather than fail on the foreign key. */
    private const val PAGE_OF =
        "(SELECT id FROM page WHERE periodId = (SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?) AND half = ?)"

    /** [putStroke] for a page whose row this showing minted: the `pageId` is resolved in the statement. */
    fun putStrokeOnPage(calendarId: String, kind: Int, date: String, half: Int, order: Long, stroke: Stroke): Statement =
        Statement(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, $PAGE_OF, ?, ?, ?, ?, ?)",
            stroke.id, calendarId, kind.toLong(), date, half.toLong(),
            order, stroke.color.toLong(), stroke.width.toDouble(), stroke.style.name, StrokeBlob.encode(stroke),
        )

    /** [touchPage] for a page whose row this showing minted. */
    fun touchPageOf(calendarId: String, kind: Int, date: String, half: Int, now: Long): Statement =
        Statement(
            "UPDATE page SET updatedAt = ? WHERE id = $PAGE_OF",
            now, calendarId, kind.toLong(), date, half.toLong(),
        )

    // ── state — the bookmark ──────

    const val KEY_LAST_VIEW = "lastView"
    const val KEY_LAST_DATE = "lastDate"
    const val KEY_LAST_HALF = "lastHalf"

    fun setState(calendarId: String, key: String, value: String): Statement =
        Statement("INSERT OR REPLACE INTO state (calendarId, key, value) VALUES (?, ?, ?)", calendarId, key, value)

    // ── reads ──────

    fun selectCalendar(id: String): Statement =
        Statement("SELECT id FROM calendar WHERE id = ?", id)

    fun selectPeriod(calendarId: String, kind: Int, date: String): Statement =
        Statement("SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?", calendarId, kind.toLong(), date)

    /** Opening a page is one join: the page under the period named by `(calendarId, kind, date)`, at [half]. */
    fun selectPage(calendarId: String, kind: Int, date: String, half: Int): Statement =
        Statement(
            "SELECT page.id AS id, page.periodId AS periodId, page.width AS width, page.height AS height " +
                "FROM page JOIN period ON period.id = page.periodId " +
                "WHERE period.calendarId = ? AND period.kind = ? AND period.date = ? AND page.half = ?",
            calendarId, kind.toLong(), date, half.toLong(),
        )

    /** The page's strokes, in writing order. */
    fun selectStrokes(pageId: String): Statement = InkSql.selectStrokes(pageId)

    /** Where a paste onto an existing page starts numbering; `-1` on an empty page. */
    fun selectMaxOrder(pageId: String): Statement =
        Statement("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM stroke WHERE pageId = ?", pageId)

    fun selectState(calendarId: String): Statement =
        Statement("SELECT key, value FROM state WHERE calendarId = ?", calendarId)

    /** The row counts in one read — the "browsing wrote nothing" proof, logged at open. */
    fun selectCounts(): Statement =
        Statement(
            "SELECT (SELECT COUNT(*) FROM period) AS periods, (SELECT COUNT(*) FROM page) AS pages, " +
                "(SELECT COUNT(*) FROM stroke) AS strokes, (SELECT COUNT(*) FROM event) AS events",
        )
}
