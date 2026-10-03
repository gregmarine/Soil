package com.symmetricalpalmtree.soil.ext

/**
 * The cloud point: what a cloud storage `<service>` declares, the caps its answers are held to,
 * and the two refusals Soil reads by their text. One provider at a time: Soil takes the first
 * trusted service it finds.
 */
object CloudContract {

    /** The action the storage `<service>` answers. */
    const val ACTION_CLOUD_STORAGE = "com.symmetricalpalmtree.soil.ext.CLOUD_STORAGE"

    /** The action of the extension's connect screen, started for a result by Soil with nothing
     *  on the Intent; the screen refuses any other caller. */
    const val ACTION_CLOUD_SCREEN = "com.symmetricalpalmtree.soil.ext.CLOUD_SCREEN"

    /** The one table of the store Soil lends the extension. */
    const val STORE_TABLE = "account"
    const val STORE_CREATE = "CREATE TABLE IF NOT EXISTS $STORE_TABLE (key TEXT PRIMARY KEY, value TEXT NOT NULL)"

    const val MAX_PATH_DEPTH = 8
    const val MAX_NAME_CHARS = 255
    const val MAX_ENTRY_ID_CHARS = 256
    const val MAX_MIME_CHARS = 128
    const val MAX_ACCOUNT_LABEL_CHARS = 254
    const val MAX_PROVIDER_NAME_CHARS = 64
    const val MAX_LIST_ENTRIES = 1_000

    /** The exact messages of the two refusals that mean something to Soil. */
    const val NOT_CONNECTED = "not connected"
    const val NETWORK = "network"

    fun isName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return false
        if (name == "." || name == "..") return false
        if (name.first().isWhitespace() || name.last().isWhitespace()) return false
        for (c in name) if (c == '/' || c == '\\' || c.isISOControl()) return false
        return true
    }

    fun isEntryId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_ENTRY_ID_CHARS) return false
        for (c in id) if (c.isWhitespace() || c.isISOControl()) return false
        return true
    }

    fun isLabel(label: String, max: Int): Boolean {
        if (label.length > max) return false
        for (c in label) if (c.isISOControl()) return false
        return true
    }

    fun isMime(mime: String): Boolean {
        if (mime.isEmpty() || mime.length > MAX_MIME_CHARS) return false
        val slash = mime.indexOf('/')
        if (slash <= 0 || slash == mime.length - 1 || mime.indexOf('/', slash + 1) >= 0) return false
        for (c in mime) if (c.isWhitespace() || c.isISOControl()) return false
        return true
    }

    fun requireValidPath(path: Array<String>?): Array<String> {
        requireNotNull(path) { "path is null" }
        require(path.size <= MAX_PATH_DEPTH) { "path has ${path.size} segments; at most $MAX_PATH_DEPTH" }
        for ((i, segment) in path.withIndex()) require(isName(segment)) { "path segment $i is not a name" }
        return path
    }
}
