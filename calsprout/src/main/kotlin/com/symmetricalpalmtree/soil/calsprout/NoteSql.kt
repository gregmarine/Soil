package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * The event note's ink — the pad's stroke statements against the event's own table.
 *
 * `note_stroke` **is** the pad's stroke row: same columns, same `StrokeCodec` format B blob, same
 * `"order"` semantics. What differs is the table's name and its parent column (`eventId`,
 * cascading from `event`), and `InkSql`'s text spells `stroke` / `pageId` — a string cannot be
 * delegated with two words changed, so the statements are written out here and `NoteSqlTest`
 * pins them: exact text, exact arguments, every one through the seam's checker.
 *
 * `"order"` is quoted (a real SQLite keyword) and a stroke row is `INSERT OR REPLACE` — safe here
 * where it is forbidden on `event`, because a stroke has no children for REPLACE's delete to take.
 */
object NoteSql : InkDocument.StrokeSql {

    private const val PUT_STROKE_HEAD = "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob)"

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement =
        Statement(
            "$PUT_STROKE_HEAD VALUES (?, ?, ?, ?, ?, ?, ?)",
            stroke.id, pageId, order, stroke.color.toLong(), stroke.width.toDouble(), stroke.style.name, StrokeBlob.encode(stroke),
        )

    override fun dropStroke(id: String): Statement =
        Statement("DELETE FROM note_stroke WHERE id = ?", id)

    /** Whether [s] is a [putStroke] of a stroke in [ids] — the one shape that only adds a row
     *  ([NoteWrite.inPlace]). The id is the put's first bind. */
    fun isPutOfAny(s: Statement, ids: Set<String>): Boolean =
        s.sql.startsWith(PUT_STROKE_HEAD) && (s.args.firstOrNull() as? Cell.Text)?.value in ids

    /** Empty an event's note, keeping the event — what erasing the whole note comes to. */
    fun clearStrokes(eventId: String): Statement =
        Statement("DELETE FROM note_stroke WHERE eventId = ?", eventId)

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The note's strokes, in writing order — one read, as the pad's. */
    fun selectStrokes(eventId: String): Statement =
        Statement("SELECT id, \"order\", color, width, style, blob FROM note_stroke WHERE eventId = ? ORDER BY \"order\"", eventId)

    /** Where new ink starts numbering; `-1` on a note with nothing on it. */
    fun selectMaxOrder(eventId: String): Statement =
        Statement("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM note_stroke WHERE eventId = ?", eventId)
}
