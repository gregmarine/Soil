package com.symmetricalpalmtree.soil.importing

/** An id out of a foreign file is used only in its canonical UUID form; anything else is replaced. */
object SafeImportId {
    private val UUID_FORM = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    fun isSafe(id: String?): Boolean = id != null && UUID_FORM.matches(id)
    fun orNull(id: String?): String? = if (isSafe(id)) id else null
}
