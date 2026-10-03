package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.Row
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.util.UUID

/** A template or a template folder, blob-free, as a listing describes it. */
data class TemplateRow(
    val id: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
    val fit: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** The stored picture's size in bytes; 0 for a folder. */
    val blobLength: Long,
)

/**
 * The paper library's rows: templates, their folders and the pins. Listings are **blob-free**;
 * [image] is the one read that costs bytes. **Blocking**: IO only, while [SoilIndex.isReady].
 *
 * The root is `parentId = ''`. Names are unique among siblings of a kind, compared exactly, as
 * the caller checks with [nameTaken] before a write.
 */
class TemplateStore(private val rows: SqlCipherRowStore = SqlCipherRowStore(SoilIndex.db())) {

    // ── Listings ──────

    fun folders(parentId: String): List<TemplateRow> =
        rows.query(Statement("$FOLDER_SELECT WHERE deletedAt IS NULL AND parentId = ? ORDER BY name", parentId)).rows.map(::folder)

    fun templates(parentId: String): List<TemplateRow> =
        rows.query(Statement("$TEMPLATE_SELECT WHERE deletedAt IS NULL AND parentId = ? ORDER BY name", parentId)).rows.map(::template)

    /** Every alive template anywhere, blob-free: what a search ranks. */
    fun allTemplates(): List<TemplateRow> =
        rows.query(Statement("$TEMPLATE_SELECT WHERE deletedAt IS NULL ORDER BY name")).rows.map(::template)

    fun template(id: String): TemplateRow? =
        rows.query(Statement("$TEMPLATE_SELECT WHERE deletedAt IS NULL AND id = ?", id)).rows.firstOrNull()?.let(::template)

    fun folder(id: String): TemplateRow? =
        rows.query(Statement("$FOLDER_SELECT WHERE deletedAt IS NULL AND id = ?", id)).rows.firstOrNull()?.let(::folder)

    /** The stored picture, or null when the row is gone or holds none. */
    fun image(id: String): ByteArray? =
        rows.query(Statement("SELECT blob FROM template WHERE deletedAt IS NULL AND id = ?", id)).rows.firstOrNull()?.blobOrNull("blob")

    /** The alive templates among [ids], by id. */
    fun aliveTemplates(ids: Collection<String>): Map<String, TemplateRow> =
        ids.distinct().chunked(400).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            rows.query(Statement("$TEMPLATE_SELECT WHERE deletedAt IS NULL AND id IN ($marks)", *chunk.toTypedArray())).rows.map(::template)
        }.associateBy { it.id }

    /** Root first, down to [folderId] itself. Empty for the root. Cycle-guarded. */
    fun ancestry(folderId: String): List<TemplateRow> {
        val out = ArrayList<TemplateRow>()
        var at: String? = folderId
        val seen = HashSet<String>()
        while (!at.isNullOrEmpty() && seen.add(at)) {
            val f = folder(at) ?: break
            out += f
            at = f.parentId
        }
        return out.reversed()
    }

    /** Whether a sibling of the kind already has [name]; [exceptId] is the row itself on a rename. */
    fun nameTaken(parentId: String, isFolder: Boolean, name: String, exceptId: String? = null): Boolean {
        val table = if (isFolder) "template_folder" else "template"
        return rows.query(
            Statement("SELECT count(*) AS n FROM $table WHERE deletedAt IS NULL AND parentId = ? AND name = ? AND id != ?", parentId, name, exceptId ?: ""),
        ).rows.first().long("n") > 0
    }

    // ── Writes ──────

    fun createFolder(name: String, parentId: String, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        rows.exec(listOf(Statement("INSERT INTO template_folder (id, parentId, name, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)", id, parentId, name, now, now)))
        return id
    }

    fun createTemplate(name: String, parentId: String, fit: Int, image: ByteArray, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        rows.exec(listOf(Statement("INSERT INTO template (id, parentId, name, fit, createdAt, updatedAt, blob) VALUES (?, ?, ?, ?, ?, ?, ?)", id, parentId, name, fit, now, now, image)))
        return id
    }

    /** A copy beside the original with [name]; null when the original is gone. */
    fun duplicate(id: String, name: String, now: Long = System.currentTimeMillis()): String? {
        val source = template(id) ?: return null
        val image = image(id) ?: return null
        return createTemplate(name, source.parentId, source.fit, image, now)
    }

    fun rename(id: String, isFolder: Boolean, name: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("UPDATE ${table(isFolder)} SET name = ?, updatedAt = ? WHERE id = ?", name, now, id)))
    }

    fun move(id: String, isFolder: Boolean, parentId: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("UPDATE ${table(isFolder)} SET parentId = ?, updatedAt = ? WHERE id = ?", parentId, now, id)))
    }

    /** False when the row is gone. */
    fun setFit(id: String, fit: Int, now: Long = System.currentTimeMillis()): Boolean =
        rows.exec(listOf(Statement("UPDATE template SET fit = ?, updatedAt = ? WHERE id = ? AND deletedAt IS NULL", fit, now, id)))[0] > 0

    /** Soft-delete, the blob cleared in the same transaction, and the pin with it. */
    fun deleteTemplate(id: String, now: Long = System.currentTimeMillis()) {
        rows.exec(
            listOf(
                Statement("UPDATE template SET deletedAt = ?, blob = NULL WHERE id = ? AND deletedAt IS NULL", now, id),
                Statement("DELETE FROM template_pin WHERE id = ?", id),
            ),
        )
    }

    /** The folder and everything under it at any depth. Answers the template ids that went. Cycle-guarded. */
    fun deleteFolderRecursive(id: String, now: Long = System.currentTimeMillis()): List<String> {
        val folders = ArrayList<String>()
        val templates = ArrayList<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val fid = stack.removeLast()
            if (!seen.add(fid)) continue
            folders += fid
            templates += templates(fid).map { it.id }
            folders(fid).forEach { stack.add(it.id) }
        }
        val statements = ArrayList<Statement>()
        for (f in folders) statements += Statement("UPDATE template_folder SET deletedAt = ? WHERE id = ? AND deletedAt IS NULL", now, f)
        for (t in templates) {
            statements += Statement("UPDATE template SET deletedAt = ?, blob = NULL WHERE id = ? AND deletedAt IS NULL", now, t)
            statements += Statement("DELETE FROM template_pin WHERE id = ?", t)
        }
        rows.exec(statements)
        return templates
    }

    // ── Pins ──────

    fun pinnedIds(): List<String> =
        rows.query(Statement("SELECT id FROM template_pin ORDER BY pinnedAt")).rows.map { it.text("id") }

    fun pin(id: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("INSERT OR IGNORE INTO template_pin (id, pinnedAt) VALUES (?, ?)", id, now)))
    }

    fun unpin(id: String) {
        rows.exec(listOf(Statement("DELETE FROM template_pin WHERE id = ?", id)))
    }

    private fun table(isFolder: Boolean) = if (isFolder) "template_folder" else "template"

    private fun template(row: Row) = TemplateRow(
        id = row.text("id"), parentId = row.text("parentId"), name = row.text("name"), isFolder = false,
        fit = row.long("fit").toInt(), createdAt = row.long("createdAt"), updatedAt = row.long("updatedAt"),
        blobLength = row.longOrNull("blobLength") ?: 0L,
    )

    private fun folder(row: Row) = TemplateRow(
        id = row.text("id"), parentId = row.text("parentId"), name = row.text("name"), isFolder = true,
        fit = 0, createdAt = row.long("createdAt"), updatedAt = row.long("updatedAt"), blobLength = 0L,
    )

    private companion object {
        const val TEMPLATE_SELECT = "SELECT id, parentId, name, fit, createdAt, updatedAt, length(blob) AS blobLength FROM template"
        const val FOLDER_SELECT = "SELECT id, parentId, name, createdAt, updatedAt FROM template_folder"
    }
}
