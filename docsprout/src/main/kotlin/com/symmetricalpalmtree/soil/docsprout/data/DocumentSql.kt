package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema.TABLE
import com.symmetricalpalmtree.soil.paper.store.Statement

/**
 * Every statement Docsprout sends for a document, as a pure builder: SQL text and bound
 * arguments and nothing else, so the shapes are JVM-tested without a database, and every one
 * passes the seam's checker.
 *
 * Every write is **idempotent**, so a write that failed and is retried converges: the rows are
 * made with `INSERT OR IGNORE` and the body is then only ever `UPDATE`d. `now` is passed in so a
 * test can pin it.
 */
object DocumentSql {

    fun insertRoot(documentId: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, createdAt, updatedAt) VALUES (?, '', 'document', ?, ?)",
        documentId, now, now,
    )

    fun insertBody(id: String, documentId: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, createdAt, updatedAt, text) VALUES (?, ?, 'body', ?, ?, '')",
        id, documentId, now, now,
    )

    /** The body, oldest first: a document has one, and a file that somehow holds two reads the first. */
    fun selectBody(documentId: String): Statement = Statement(
        "SELECT id, text FROM $TABLE WHERE parentId = ? AND type = 'body' AND deletedAt IS NULL ORDER BY createdAt, id LIMIT 1",
        documentId,
    )

    fun setBody(bodyId: String, markdown: String, now: Long): Statement =
        Statement("UPDATE $TABLE SET text = ?, updatedAt = ? WHERE id = ?", markdown, now, bodyId)

    /** A Bible link taken off: its words and its wire, remembered under the root. */
    fun insertUnlinked(id: String, documentId: String, words: String, wire: String, now: Long): Statement = Statement(
        "INSERT OR IGNORE INTO $TABLE (id, parentId, type, createdAt, updatedAt, text, refId) VALUES (?, ?, 'bible_unlinked', ?, ?, ?, ?)",
        id, documentId, now, now, words, wire,
    )

    fun selectUnlinked(documentId: String): Statement = Statement(
        "SELECT text, refId FROM $TABLE WHERE parentId = ? AND type = 'bible_unlinked' AND deletedAt IS NULL",
        documentId,
    )
}
