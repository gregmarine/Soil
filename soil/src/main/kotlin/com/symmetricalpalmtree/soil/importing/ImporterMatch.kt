package com.symmetricalpalmtree.soil.importing

/** Which importer takes a picked document: by the extension of its name, never by MIME. */
object ImporterMatch {

    const val ANY_TYPE = "*/*"

    fun extensionOf(displayName: String): String {
        val name = displayName.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun matching(declared: List<List<String>>, displayName: String): List<Int> {
        val ext = extensionOf(displayName)
        if (ext.isEmpty()) return emptyList()
        return declared.indices.filter { i -> declared[i].any { it.equals(ext, ignoreCase = true) } }
    }

    /** The picker's filter: every declared MIME type, and anything, since providers lie about types. */
    fun mimeFilter(declared: List<List<String>>): Array<String> {
        val seen = LinkedHashSet<String>()
        for (list in declared) seen.addAll(list)
        seen.add(ANY_TYPE)
        return seen.toTypedArray()
    }
}
