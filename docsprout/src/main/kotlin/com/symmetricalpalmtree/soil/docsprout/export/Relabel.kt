package com.symmetricalpalmtree.soil.docsprout.export

import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema

/**
 * The statements that give a document file another id: the root row, and every row parented to
 * it. Pure, and run by Soil, never by this app.
 */
object Relabel {

    fun statements(oldId: String, newId: String): List<String> {
        require(oldId.isNotBlank() && newId.isNotBlank() && oldId != newId) { "relabel needs two different ids" }
        require(oldId.all { it.isLetterOrDigit() || it == '-' } && newId.all { it.isLetterOrDigit() || it == '-' }) { "ids are plain" }
        val t = DocumentSchema.TABLE
        return listOf(
            "UPDATE $t SET id = '$newId' WHERE id = '$oldId'",
            "UPDATE $t SET parentId = '$newId' WHERE parentId = '$oldId'",
        )
    }
}
