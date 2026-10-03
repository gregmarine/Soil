package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.util.UUID

/** The index's `export_preset` rows. Blocking: IO only, while the index is open. */
class ExportPresetStore(private val rows: SqlCipherRowStore = SqlCipherRowStore(SoilIndex.db())) {

    fun all(): List<ExportPresets.Row> =
        rows.query(Statement("SELECT id, name, json FROM export_preset ORDER BY name COLLATE NOCASE, id")).rows.mapNotNull { r ->
            ExportPreset.decode(r.text("json"))?.let { ExportPresets.Row(r.text("id"), r.text("name"), it) }
        }

    fun nameTaken(name: String, exceptId: String? = null): Boolean =
        rows.query(Statement("SELECT count(*) AS n FROM export_preset WHERE name = ? AND id != ?", name, exceptId ?: "")).rows.first().long("n") > 0

    fun create(name: String, preset: ExportPreset, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        rows.exec(listOf(Statement("INSERT INTO export_preset (id, name, json, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)", id, name, ExportPreset.encode(preset), now, now)))
        return id
    }

    fun rename(id: String, name: String, now: Long = System.currentTimeMillis()) {
        rows.exec(listOf(Statement("UPDATE export_preset SET name = ?, updatedAt = ? WHERE id = ?", name, now, id)))
    }

    fun delete(id: String) {
        rows.exec(listOf(Statement("DELETE FROM export_preset WHERE id = ?", id)))
    }
}
