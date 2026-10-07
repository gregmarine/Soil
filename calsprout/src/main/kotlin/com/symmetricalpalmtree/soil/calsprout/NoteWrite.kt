package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * What the event note contributes to one save: the stroke statements that ride the
 * event's transaction, and the stroke ids this save **minted** — what a failed multi-batch write on
 * an *existing* event gives back, one `DELETE` each (the calendar's placement rule; a new event's
 * compensation is its row's delete and the cascade).
 *
 * Which of the two shapes a save takes is the store's answer, not the screen's: [EventStore.edit]
 * decides the id the edited fields land under and asks for the note **for that id**. In place
 * (the id the note was loaded for) the note's pending op log is the write; under a fresh id — a
 * *this occurrence* override or a new *following* series — the whole note is **copied** with fresh
 * stroke ids ([copy]), because `note_stroke.id` is the primary key and a re-parented row would
 * steal the original series' note rather than copy it.
 *
 * **Two halves.** [additions] are the statements that only add rows this save
 * minted — a put of a minted stroke, or the whole copy under a new id — and [mutations] are the
 * op log's statements over rows that existed before the save: a drop of a loaded stroke, or a
 * re-put of one the lasso moved. [EventWrites.save] sends every addition ahead of every mutation,
 * and the event row's own rewrite behind both, so a multi-batch write that fails part-way leaves
 * what existed as it was (the additions are what the compensation gives back).
 */
class NoteWrite(val additions: List<Statement>, val mutations: List<Statement>, val mintedStrokeIds: List<String>) {

    /** Both halves in the order they are sent: additions first. */
    val statements: List<Statement> get() = additions + mutations

    companion object {
        /** No note at all — a fields-only save. */
        val NONE = NoteWrite(emptyList(), emptyList(), emptyList())

        /**
         * The note's pending op log, written **in place** under the id it was loaded for: a
         * statement naming a stroke in [minted] is an addition, every other one — a drop, or a
         * re-put of a loaded stroke — a mutation. Order within each half is the log's own.
         */
        fun inPlace(statements: List<Statement>, minted: List<String>): NoteWrite {
            val mintedSet = minted.toHashSet()
            val (additions, mutations) = statements.partition { NoteSql.isPutOfAny(it, mintedSet) }
            return NoteWrite(additions, mutations, minted)
        }

        /**
         * The whole note — [entries], `(order, stroke)` in writing order — put under [eventId] with
         * a fresh id from [mintId] for every stroke, orders kept. No minted list: the copy lands
         * under an id this save created, whose compensation is the row's own delete.
         */
        fun copy(entries: List<Pair<Long, Stroke>>, eventId: String, mintId: () -> String): NoteWrite =
            NoteWrite(entries.map { (order, s) -> NoteSql.putStroke(eventId, order, s.copy(id = mintId())) }, emptyList(), emptyList())
    }
}
