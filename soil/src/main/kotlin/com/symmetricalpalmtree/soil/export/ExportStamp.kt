package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.data.item.ItemMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB

/**
 * What an export writes into the item's own meta table so an import can place it: the name the
 * library had for it and the folders it was in, root first. Soil's table, Soil's keys; an app
 * cannot reach it.
 */
object ExportStamp {

    const val KEY_EXPORTED_AT = "exportedAt"
    const val KEY_FOLDER_PATH = "folderPath"

    @Serializable
    data class Folder(val id: String, val name: String)

    class Stamp(val name: String, val folderPath: List<Folder>, val exportedAt: Long = System.currentTimeMillis())

    private val json = Json { ignoreUnknownKeys = true }

    fun encodePath(path: List<Folder>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Folder.serializer()), path)

    fun decodePath(text: String?): List<Folder> =
        if (text.isNullOrEmpty()) emptyList() else runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Folder.serializer()), text) }.getOrDefault(emptyList())

    fun write(db: ZeticDB, stamp: Stamp) {
        for ((key, value) in listOf(ItemMeta.KEY_NAME to stamp.name, KEY_EXPORTED_AT to stamp.exportedAt.toString(), KEY_FOLDER_PATH to encodePath(stamp.folderPath))) {
            db.execSQL(ItemMeta.PUT, arrayOf(key, value))
        }
    }
}
