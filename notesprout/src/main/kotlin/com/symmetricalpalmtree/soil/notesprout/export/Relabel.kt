package com.symmetricalpalmtree.soil.notesprout.export

import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema

/**
 * The statements that give a notebook file another id: the root row, every page's parent, and
 * the payload of every link that names this notebook (a link's own pages travel as
 * `KIND_PAGE`, without the id; an item link to itself names it). Ids are UUIDs, so a textual
 * replace inside a payload can touch nothing else. Pure, and run by Soil, never by this app.
 */
object Relabel {

    fun statements(oldId: String, newId: String): List<String> {
        require(oldId.isNotBlank() && newId.isNotBlank() && oldId != newId) { "relabel needs two different ids" }
        require(oldId.all { it.isLetterOrDigit() || it == '-' } && newId.all { it.isLetterOrDigit() || it == '-' }) { "ids are plain" }
        val t = NotebookSchema.TABLE
        return listOf(
            "UPDATE $t SET id = '$newId' WHERE id = '$oldId'",
            "UPDATE $t SET parentId = '$newId' WHERE parentId = '$oldId'",
            "UPDATE $t SET text = replace(text, '$oldId', '$newId') WHERE type = '${NotebookSchema.TYPE_LINK}' AND text LIKE '%$oldId%'",
        )
    }
}
