package com.symmetricalpalmtree.soil.docsprout.data

import android.util.Log
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.RowStore
import java.util.UUID

/**
 * The document table over its [RowStore]. **Blocking**: every call runs on `Dispatchers.IO`,
 * never Main.
 *
 * Every SQL string lives in [DocumentSql]. Any failure at all becomes [StoreUnavailable], which
 * is what the screen answers to. Nothing of the document's text is ever logged.
 */
class DocumentStore(private val store: RowStore, private val documentId: String, private val newId: () -> String = { UUID.randomUUID().toString() }) {

    private var bodyId: String? = null

    /**
     * The document's Markdown. A file Soil made and nobody has written yet is given its root and
     * an empty body here, and reads as empty.
     */
    fun load(now: Long = System.currentTimeMillis()): String = guard {
        read() ?: run {
            store.exec(listOf(DocumentSql.insertRoot(documentId, now), DocumentSql.insertBody(newId(), documentId, now)))
            read() ?: throw IllegalStateException("the body was not made")
        }
    }

    /** The whole document, replaced. Only after a [load]. */
    fun save(markdown: String, now: Long = System.currentTimeMillis()) {
        val id = bodyId ?: throw StoreUnavailable(IllegalStateException("save before load"))
        require(DocumentLimits.fits(markdown)) { "the document is over the limit" }
        guard {
            // The words, and in the same batch the file's link mirror made to say what the
            // words link to: the two cannot disagree.
            val batch = listOf(DocumentSql.setBody(id, markdown, now)) + DocumentLinks.mirror(documentId, DocumentLinks.targets(markdown, documentId))
            val changed = store.exec(batch)
            if (changed.firstOrNull() != 1L) throw IllegalStateException("the body row is gone")
        }
    }

    /** The Bible links the writer took off, as [BibleUnlinked] keys. A row this build cannot read is dropped. */
    fun unlinked(): Set<String> = guard {
        store.query(DocumentSql.selectUnlinked(documentId)).rows.mapNotNullTo(HashSet()) { row ->
            val words = row.textOrNull("text") ?: return@mapNotNullTo null
            val wire = row.textOrNull("refId") ?: return@mapNotNullTo null
            BibleUnlinked.key(words, wire)
        }
    }

    /** Remember a Bible link taken off, so the pass never puts it back. Its own batch. */
    fun forget(words: String, wire: String, now: Long = System.currentTimeMillis()) = guard {
        store.exec(listOf(DocumentSql.insertUnlinked(newId(), documentId, BibleUnlinked.words(words), wire, now)))
        Unit
    }

    /** A reference allowed again, by its wire: the pass may link it once more. Its own batch. */
    fun allowAgain(wire: String, now: Long = System.currentTimeMillis()) = guard {
        store.exec(listOf(DocumentSql.deleteUnlinked(documentId, wire, now)))
        Unit
    }

    private fun read(): String? {
        val row = store.query(DocumentSql.selectBody(documentId)).rows.firstOrNull() ?: return null
        bodyId = row.text("id")
        return row.textOrNull("text").orEmpty()
    }

    private inline fun <T> guard(body: () -> T): T = try {
        body()
    } catch (e: StoreUnavailable) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "the store failed: ${e.javaClass.simpleName}")
        throw StoreUnavailable(e)
    }

    private companion object {
        const val TAG = "DocumentStore"
    }
}

/**
 * How long a document may be. The seam carries a value of up to 6 MiB; a document stops a little
 * short of it, counted as the UTF-8 it is stored as.
 */
object DocumentLimits {
    const val MAX_BODY_BYTES = 5 * 1024 * 1024

    fun fits(markdown: String): Boolean {
        // The cheap answers first: a char is at most three bytes of UTF-8 (a surrogate pair is
        // two chars and four bytes), so most documents never need counting.
        if (markdown.length * 3L <= MAX_BODY_BYTES) return true
        if (markdown.length > MAX_BODY_BYTES) return false
        var bytes = 0L
        for (c in markdown) {
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isSurrogate(c) -> 2
                else -> 3
            }
            if (bytes > MAX_BODY_BYTES) return false
        }
        return true
    }
}
