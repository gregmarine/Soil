package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.CloudEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * Drive REST v3, as the CLOUD_STORAGE seam needs it (arc 25 / V2). Read from og Notesprout's
 * `DriveApiClient` and **re-derived**, not copied — the shape is different because this seam speaks
 * paths of names under a provider-owned root, and answers `CloudEntry` rather than bare ids.
 *
 * The scope is `drive.file` (`DRIVE_PLAN.md` decision 14): the app sees only what it created. So
 * every path resolves under **one root folder** named by `BuildConfig.ROOT_FOLDER_NAME`
 * (`Notesprout SN`, or `Notesprout SN Dev` in a debug build — decision 9), created directly under
 * My Drive on first use and its id cached in the store. Because SN shares og's OAuth client it
 * *can* see og's trees; it never lists outside its own root, by construction — nothing here ever
 * names a parent it did not resolve from that root.
 *
 * **Three rules that come from Drive itself and are not negotiable:**
 *  - Drive allows **same-named siblings**. So every write is find-then-update, never blind create,
 *    and a find takes the FIRST match in Drive's own order. Nothing is ever created beside an
 *    existing name.
 *  - A provider's metadata can lag its own write, so the entry a write answers is *corroboration*,
 *    not proof — the host's rule for disagreement is *check the file*, never delete.
 *  - `size` is a string and `modifiedTime` is RFC 3339 (see [DriveJson]).
 *
 * **Retry discipline.** A call whose body can be replayed retries once after a 401 (refresh the
 * token, try again; a second 401 is `not connected`). A call that **streams the host's fd** does
 * not: an fd cannot be rewound, so a replay would upload a truncated file. Those take a token
 * checked for freshness up front — [DriveAuth.EXPIRY_SKEW_MS] exists for exactly this — and a 401
 * on them is `not connected` at once.
 *
 * **Logging is shape only**: a method, an http code, a byte count. Never a URL (a query carries the
 * user's file names, and a resumable session URI is a bearer of its own), never a name, never a
 * token, never the account.
 */
class DriveApi(
    private val transport: HttpTransport,
    private val tokens: TokenSource,
    private val store: DriveStore,
    private val rootFolderName: String,
    /** Folder ids by path, process-wide by default (arc 34 / M9b) — see [FolderCache]. */
    private val folders: FolderCache = DriveFolders.cache,
) {

    // ── The account ──────────────────────────────────────────────────────────

    /** The signed-in account's email, or `""` when Drive would not say. Never logged. */
    fun about(): String {
        val reply = call { HttpRequest("GET", DriveRest.ABOUT, bearer(it)) }
        if (!reply.ok) throw DriveFailures.forHttp(reply.code)
        return DriveJson.parseAboutEmail(reply.body)
    }

    // ── Folders and files by name ────────────────────────────────────────────

    /** The first child of [parentId] named [name], or null. */
    fun findChild(parentId: String, name: String, foldersOnly: Boolean): CloudEntry? {
        val reply = call { HttpRequest("GET", DriveRest.findUrl(parentId, name, foldersOnly), bearer(it)) }
        if (!reply.ok) throw DriveFailures.forHttp(reply.code)
        return DriveJson.parseFileList(reply.body).entries.firstOrNull()
    }

    /**
     * Create a folder named [name] under [parentId]. Callers go through [ensureFolder]. A folder
     * that comes back `trashed` was made under a parent in the trash (a cached id trashed on the
     * web: the finds under it see nothing, since its children are trashed too) and answers as
     * Drive's 404, so [underPath] evicts the path and re-resolves it from the root.
     */
    fun createFolder(parentId: String, name: String): CloudEntry {
        val body = DriveJson.folderBody(name, parentId)
        val reply = call {
            HttpRequest("POST", DriveRest.createUrl(), bearer(it), HttpBody.Text(DriveHttp.JSON, body))
        }
        if (!reply.ok) throw DriveFailures.forHttp(reply.code)
        val entry = DriveJson.parseFile(reply.body) ?: throw IllegalStateException(DriveJson.UNREADABLE)
        if (DriveJson.isTrashed(reply.body)) {
            Slog.d(TAG) { "a folder was made under a trashed parent — treated as gone" }
            throw DriveFailures.forHttp(404)
        }
        return entry
    }

    /** Find-or-create — and **find first**, always: nothing is created beside an existing name. */
    fun ensureFolder(parentId: String, name: String): CloudEntry =
        findChild(parentId, name, foldersOnly = true) ?: createFolder(parentId, name)

    /** Every child of [parentId], paged, sorted folders-then-files by name, truncated at the seam's
     *  ceiling rather than failed. */
    fun listChildren(parentId: String): List<CloudEntry> {
        val out = ArrayList<CloudEntry>()
        var pageToken: String? = null
        do {
            val token = pageToken
            val reply = call { HttpRequest("GET", DriveRest.listUrl(parentId, token), bearer(it)) }
            if (!reply.ok) throw DriveFailures.forHttp(reply.code)
            val page = DriveJson.parseFileList(reply.body)
            out.addAll(page.entries)
            // Stop asking once there is already more than the seam will carry: the extra pages
            // could only be thrown away.
            pageToken = if (out.size >= CloudContract.MAX_LIST_ENTRIES) null else page.nextPageToken
        } while (pageToken != null)
        return DriveJson.truncated(DriveJson.sorted(out))
    }

    // ── The provider's root, and paths under it ──────────────────────────────

    /** Drop every cached folder id — the account they were resolved under is gone. */
    fun forgetFolders() = folders.clear()

    /**
     * The id of this build's root folder, find-or-create: the process cache, then the store, then
     * Drive. An id from the store is **checked once per process** (one metadata read, then it lives
     * in the process cache): a root trashed on the web still answers 200 to every call under it, so
     * no 404 would ever tell; a trashed or deleted one is dropped and found or made again. After
     * that, never probed (arc 34 / M9b — the old `exists` read per call was one metadata round-trip
     * per upload): an id Drive no longer knows answers a 404 to the first call under it, and
     * [underPath] drops it (memory and store) and re-resolves once; one trashed meanwhile is told
     * by the `trashed` on a folder made or a file written under it ([createFolder], [upload]).
     */
    fun rootId(): String {
        folders.get(emptyList())?.let { return it }
        val cached = store.value(DriveSql.Keys.ROOT_FOLDER_ID)?.takeIf { CloudContract.isEntryId(it) }
        if (cached != null) {
            if (isLiveFolder(cached)) {
                folders.put(emptyList(), cached)
                return cached
            }
            Slog.d(TAG) { "the stored root is trashed or gone — re-resolving" }
            store.remove(DriveSql.Keys.ROOT_FOLDER_ID)
        }
        val entry = ensureFolder(DriveRest.MY_DRIVE, rootFolderName)
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, entry.id)
        folders.put(emptyList(), entry.id)
        return entry.id
    }

    /** The folder id at [path] (the root when empty), or null when a segment is not there. */
    fun findPath(path: Array<String>): String? = underPath(path) { walkPath(path, create = false) }

    /**
     * Find-or-create every segment and answer the last (the root when [path] is empty). A segment
     * answered from the cache is an entry by id and name only — its size and time are 0, which no
     * caller of this reads (the backup leg and the browser want the folder to exist; the uploads
     * want its id).
     */
    fun ensurePath(path: Array<String>): CloudEntry = underPath(path) {
        val id = walkPath(path, create = true)!!
        CloudEntry(id, path.lastOrNull() ?: rootFolderName, true, 0L, 0L)
    }

    /** The walk itself: cached ids where the cache has them, one find (or find-or-create) per
     *  segment it does not, each answer cached as it lands. Null when a segment is not there and
     *  [create] is false. */
    private fun walkPath(path: Array<String>, create: Boolean): String? {
        var id = rootId()
        val walked = ArrayList<String>(path.size)
        for (segment in path) {
            walked += segment
            val hit = folders.get(walked)
            if (hit != null) { id = hit; continue }
            val entry = if (create) ensureFolder(id, segment) else findChild(id, segment, foldersOnly = true) ?: return null
            id = entry.id
            folders.put(walked, id)
        }
        return id
    }

    /**
     * Run [block] over the cached ids of [path]; on Drive's 404 — an id under that path is gone —
     * evict the path (every prefix, the root's store row included, and every descendant) and run
     * it **once** more. Everything under this is metadata-only and replayable; the byte-streaming
     * leg of an upload is outside it, since an fd cannot be rewound. Never nested — a wrapped call
     * runs the raw [walkPath], not [findPath] / [ensurePath], or one 404 would cost four attempts.
     */
    private inline fun <T> underPath(path: Array<String>, block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            if (!DriveFailures.isNotFound(e)) throw e
            Slog.d(TAG) { "a cached folder id is gone (${path.size} segment(s) deep) — re-resolving once" }
            folders.evict(path.toList())
            store.remove(DriveSql.Keys.ROOT_FOLDER_ID)
            block()
        }

    /** The seam's `list`: a path whose folder is not there answers **empty**, never a failure. */
    fun list(path: Array<String>): List<CloudEntry> = underPath(path) {
        val parentId = walkPath(path, create = false) ?: return@underPath emptyList()
        listChildren(parentId)
    }

    // ── Bytes ────────────────────────────────────────────────────────────────

    /**
     * Write [source]'s [expectedBytes] as [name] under [path], creating the folders on the way and
     * **replacing by name** (same id kept) when a file of that name is already there. Answers the
     * entry as Drive reports it after the write.
     */
    fun upload(
        path: Array<String>,
        name: String,
        mime: String,
        source: InputStream,
        expectedBytes: Long,
    ): CloudEntry {
        // The folder walk and the name find are metadata-only and ride the 404 re-resolve together
        // (arc 34 / M9b): a stale cached parent answers its 404 to the find, not to the bytes.
        val (parentId, existing) = underPath(path) {
            val id = walkPath(path, create = true)!!
            id to findChild(id, name, foldersOnly = false)
        }
        if (existing != null && existing.isFolder) {
            // A folder already owns this name. Replacing it is not what the host meant, and Drive
            // would happily create a file beside it — the one thing this seam promises not to do.
            throw IllegalStateException("name is a folder")
        }
        val written = if (DriveMultipart.useMultipart(expectedBytes)) {
            multipartUpload(parentId, existing?.id, name, mime, source, expectedBytes)
        } else {
            resumableUpload(parentId, existing?.id, name, mime, source, expectedBytes)
        }
        if (written.trashed) {
            // A cached folder on the path was trashed on the web: the file landed in the trash with
            // it. The fd cannot be replayed, so this call fails; the eviction makes the next one
            // re-resolve the path from the root and write to a folder that is really there.
            Slog.d(TAG) { "upload landed under a trashed folder (${path.size} segment(s) deep) — evicting" }
            folders.evict(path.toList())
            store.remove(DriveSql.Keys.ROOT_FOLDER_ID)
            throw IllegalStateException(TRASHED)
        }
        Slog.d(TAG) { "upload ok, $expectedBytes B, replaced=${existing != null}" }
        return written.entry
    }

    /** Stream the file [fileId] into [out] and answer the byte count. */
    fun download(fileId: String, out: OutputStream): Long {
        val meta = metadata(fileId)
        require(!meta.isFolder) { "entry is a folder" }
        val token = tokens.access()
        val reply = transport.stream(HttpRequest("GET", DriveRest.mediaUrl(fileId), bearer(token)), out)
        when {
            reply.code == 404 -> throw IllegalStateException(GONE)
            reply.code == 401 -> {
                tokens.invalidate()
                throw DriveFailures.notConnected()
            }
            !reply.ok -> throw DriveFailures.forHttp(reply.code)
        }
        Slog.d(TAG) { "download ok, ${reply.bytes} B" }
        return reply.bytes
    }

    /** The entry [fileId] names. An id Drive no longer knows is [GONE]. */
    fun metadata(fileId: String): CloudEntry = described(fileId).entry

    /** An entry with Drive's `trashed`, which a [CloudEntry] does not carry. */
    private class Described(val entry: CloudEntry, val trashed: Boolean)

    private fun described(fileId: String): Described {
        val reply = call { HttpRequest("GET", DriveRest.metadataUrl(fileId), bearer(it)) }
        if (reply.code == 404) throw IllegalStateException(GONE)
        if (!reply.ok) throw DriveFailures.forHttp(reply.code)
        val entry = DriveJson.parseFile(reply.body) ?: throw IllegalStateException(DriveJson.UNREADABLE)
        return Described(entry, DriveJson.isTrashed(reply.body))
    }

    /** Whether [id] is still a folder outside the trash. Deleted (404) is false; any other failure
     *  is thrown, since the network failing says nothing about the folder. */
    private fun isLiveFolder(id: String): Boolean {
        val d = try {
            described(id)
        } catch (e: IllegalStateException) {
            if (e.message == GONE) return false
            throw e
        }
        return d.entry.isFolder && !d.trashed
    }

    /** Delete a file or folder. Already gone counts as done — the seam says idempotent. */
    fun delete(fileId: String) {
        val reply = call { HttpRequest("DELETE", DriveRest.deleteUrl(fileId), bearer(it)) }
        if (reply.code == 204 || reply.code == 200 || reply.code == 404) {
            Slog.d(TAG) { "delete http ${reply.code}" }
            return
        }
        throw DriveFailures.forHttp(reply.code)
    }

    // ── The two upload shapes ────────────────────────────────────────────────

    private fun multipartUpload(
        parentId: String,
        existingId: String?,
        name: String,
        mime: String,
        source: InputStream,
        expectedBytes: Long,
    ): Described {
        val meta = DriveJson.uploadMetaBody(name, if (existingId == null) parentId else null)
        val prefix = DriveMultipart.prefix(meta, mime)
        val suffix = DriveMultipart.suffix()
        val length = DriveMultipart.length(prefix, suffix, expectedBytes)
        val url = if (existingId == null) DriveRest.multipartCreateUrl() else DriveRest.multipartUpdateUrl(existingId)
        val method = if (existingId == null) "POST" else "PATCH"
        val body = HttpBody.Streamed(DriveMultipart.contentType(), length) { out ->
            out.write(prefix)
            ExactCopy.copy(source, out, expectedBytes)
            out.write(suffix)
        }
        val reply = callOnce { HttpRequest(method, url, bearer(it), body) }
        if (!reply.ok) throw DriveFailures.forHttp(reply.code)
        return entryFrom(reply.body)
    }

    private fun resumableUpload(
        parentId: String,
        existingId: String?,
        name: String,
        mime: String,
        source: InputStream,
        expectedBytes: Long,
    ): Described {
        val meta = DriveJson.uploadMetaBody(name, if (existingId == null) parentId else null)
        val initUrl = if (existingId == null) DriveRest.resumableCreateUrl() else DriveRest.resumableUpdateUrl(existingId)
        val initMethod = if (existingId == null) "POST" else "PATCH"
        // The session leg carries no bytes, so it may retry after a 401 like any other call.
        val init = call {
            HttpRequest(
                initMethod,
                initUrl,
                bearer(it) + mapOf(
                    "X-Upload-Content-Type" to mime,
                    "X-Upload-Content-Length" to expectedBytes.toString(),
                ),
                HttpBody.Text(DriveHttp.JSON, meta),
            )
        }
        if (!init.ok) throw DriveFailures.forHttp(init.code)
        val session = init.header("location")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("no upload session")
        // The session URI carries its own authorization; a bearer on it is neither needed nor sent.
        val put = transport.send(
            HttpRequest(
                "PUT",
                session,
                emptyMap(),
                HttpBody.Streamed(mime, expectedBytes) { out -> ExactCopy.copy(source, out, expectedBytes) },
            )
        )
        if (!put.ok) throw DriveFailures.forHttp(put.code)
        return entryFrom(put.body)
    }

    /** The entry a write answered — or, if the reply named only an id this seam could not read as
     *  an entry, one more metadata read. The write already happened; refusing to describe it would
     *  be worse than a round trip. */
    private fun entryFrom(body: String): Described {
        DriveJson.parseFile(body)?.let { return Described(it, DriveJson.isTrashed(body)) }
        val id = DriveJson.parseId(body) ?: throw IllegalStateException(DriveJson.UNREADABLE)
        return described(id)
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun bearer(accessToken: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $accessToken")

    /** A replayable call: one 401 costs a refresh and a second try; a second 401 is the account. */
    private fun call(build: (String) -> HttpRequest): HttpReply {
        var reply = transport.send(build(tokens.access()))
        if (reply.code == 401) {
            tokens.invalidate()
            reply = transport.send(build(tokens.access()))
            if (reply.code == 401) {
                tokens.invalidate()
                throw DriveFailures.notConnected()
            }
        }
        return reply
    }

    /** A call whose body streams the host's fd — never replayed. */
    private fun callOnce(build: (String) -> HttpRequest): HttpReply {
        val reply = transport.send(build(tokens.access()))
        if (reply.code == 401) {
            tokens.invalidate()
            throw DriveFailures.notConnected()
        }
        return reply
    }

    companion object {
        private const val TAG = "DriveApi"

        /** The provider no longer knows this id. */
        const val GONE: String = "gone"

        /** An upload landed under a folder in the trash; the next call re-resolves the path. */
        const val TRASHED: String = "folder in trash"

        /**
         * The account's email read with a bare access token — the one call the connect screen makes
         * before there is anything in the store to refresh from. Best effort: any failure answers
         * `""`, because a connection with no label is legal on the wire and refusing a sign-in over
         * a display string would be absurd. **Never logged.**
         */
        fun aboutEmail(transport: HttpTransport, accessToken: String): String = try {
            val reply = transport.send(
                HttpRequest("GET", DriveRest.ABOUT, mapOf("Authorization" to "Bearer $accessToken"))
            )
            if (reply.ok) DriveJson.parseAboutEmail(reply.body) else ""
        } catch (e: Exception) {
            ""
        }
    }
}
