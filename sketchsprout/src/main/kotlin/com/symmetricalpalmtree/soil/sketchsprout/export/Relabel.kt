package com.symmetricalpalmtree.soil.sketchsprout.export

import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema

/**
 * The statements that give a sketchbook file another id: the root row and every page's and
 * template's parent. A sketchbook holds no link that names itself, so nothing else carries the
 * id. Pure, and run by Soil at import, never by this app.
 */
object Relabel {

    fun statements(oldId: String, newId: String): List<String> {
        require(oldId.isNotBlank() && newId.isNotBlank() && oldId != newId) { "relabel needs two different ids" }
        require(oldId.all { it.isLetterOrDigit() || it == '-' } && newId.all { it.isLetterOrDigit() || it == '-' }) { "ids are plain" }
        val t = SketchbookSchema.TABLE
        return listOf(
            "UPDATE $t SET id = '$newId' WHERE id = '$oldId'",
            "UPDATE $t SET parentId = '$newId' WHERE parentId = '$oldId'",
        )
    }
}
