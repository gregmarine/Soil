package com.symmetricalpalmtree.soil.data.item

/**
 * **What a file says of itself.** Every item file carries one table of Soil's own, `soil_meta`,
 * so a file can be told apart from any other with nothing but the file: what kind it is, which
 * item, and what it was called. The index is the record the library is drawn from; this is what
 * the index can be rebuilt from.
 *
 * An app cannot reach it. The `soil_` names are reserved by the statement checker.
 */
object ItemMeta {

    const val TABLE = "soil_meta"

    /** The version of this table's own keys, not of the app's schema. */
    const val FORMAT = "1"

    const val KEY_FORMAT = "format"
    const val KEY_ID = "id"
    const val KEY_KIND = "kind"
    const val KEY_NAME = "name"
    const val KEY_CREATED_AT = "createdAt"

    const val CREATE = "CREATE TABLE IF NOT EXISTS $TABLE (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
    const val EXISTS = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$TABLE'"
    const val READ = "SELECT key, value FROM $TABLE"
    const val PUT = "INSERT INTO $TABLE (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"

    /** The rows a new file is given. */
    fun rows(id: String, kind: String, name: String, createdAt: Long): Map<String, String> = linkedMapOf(
        KEY_FORMAT to FORMAT,
        KEY_ID to id,
        KEY_KIND to kind,
        KEY_NAME to name,
        KEY_CREATED_AT to createdAt.toString(),
    )

    enum class Verdict {
        OK,
        /** The file is of another kind, or is another item's. It is not opened for rows. */
        NOT_THIS_ITEM,
        /** The file says nothing of itself: it was not made by Soil as an item. */
        NO_META,
        /** Written by a later Soil, whose keys this one does not know. */
        NEWER,
    }

    /** Whether the file whose meta is [found] is the item [id] of [kind]. Null: no table. Pure. */
    fun verdict(found: Map<String, String>?, id: String, kind: String): Verdict {
        if (found == null) return Verdict.NO_META
        val format = found[KEY_FORMAT]?.toIntOrNull() ?: return Verdict.NO_META
        return when {
            format > FORMAT.toInt() -> Verdict.NEWER
            found[KEY_ID] != id || found[KEY_KIND] != kind -> Verdict.NOT_THIS_ITEM
            else -> Verdict.OK
        }
    }
}
