package com.symmetricalpalmtree.soil.notesprout.objects

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.markdown.HeadingPrefix
import com.symmetricalpalmtree.soil.markdown.HeadingTypography
import com.symmetricalpalmtree.soil.paper.store.Row
import com.symmetricalpalmtree.soil.bibleref.ReferenceCodec
import com.symmetricalpalmtree.soil.seam.BibleAddress
import kotlin.math.max

/**
 * **What a `link` row's `text` holds**, byte for byte Notesprout SN's grammar, so a converted
 * notebook's links read: `"L1|<chrome>|<kind>|<itemId>|<pageId>"`. A versioned tag, `|` as the
 * separator (never in an id), the chrome `0|1`, the kind `0|1|2`, and an empty slot for each id
 * the kind does not carry.
 *
 * | kind | carries |
 * |---|---|
 * | [KIND_PAGE] | a page of the link's own notebook: no item id |
 * | [KIND_ITEM] | another item, whole: no page id |
 * | [KIND_ITEM_PAGE] | a page of another item |
 * | [KIND_BIBLE] | a passage of scripture: the wire in the item slot, no page id |
 * | [KIND_BIBLE_TEXT] | the verses of a passage, as text on the page: the same slots |
 *
 * The item slot is SN's notebook slot: in Soil a link may point at any kind of item, and what
 * kind it is, the library says, never the payload. A Bible kind's slot holds a wire
 * (`JHN:3:14-3:18`, `:bible-ref`'s codec), byte for byte as SN wrote it, so an SN notebook's
 * Bible links read; a decoded Bible payload has no item id, so nothing that re-points an item
 * link can reach the wire.
 *
 * [encode] throws on a caller's mistake; [decode] never throws. A payload it cannot read is a
 * link whose content still draws, without chrome, and whose follow explains itself.
 */
object LinkPayload {

    const val VERSION = "L1"
    private const val SEP = '|'

    const val CHROME_NONE = 0
    const val CHROME_UNDERLINE = 1
    const val KIND_PAGE = 0
    const val KIND_ITEM = 1
    const val KIND_ITEM_PAGE = 2
    const val KIND_BIBLE = 3
    const val KIND_BIBLE_TEXT = 4

    /** The file is untrusted input: a payload is capped both ways. */
    const val MAX_PAYLOAD_CHARS = 2_000
    const val MAX_ID_CHARS = 64

    data class Decoded(
        val chrome: Int,
        val kind: Int,
        val itemId: String?,
        val pageId: String?,
        /** The wire when [kind] is [KIND_BIBLE] or [KIND_BIBLE_TEXT], else null. Never logged. */
        val reference: String? = null,
    )

    fun encode(chrome: Int, kind: Int, itemId: String?, pageId: String?): String {
        require(chrome == CHROME_NONE || chrome == CHROME_UNDERLINE) { "unknown chrome $chrome" }
        when (kind) {
            KIND_PAGE -> {
                require(itemId == null) { "a page link carries no item id" }
                requireId(pageId, "pageId")
            }
            KIND_ITEM -> {
                requireId(itemId, "itemId")
                require(pageId == null) { "an item link carries no page id" }
            }
            KIND_ITEM_PAGE -> {
                requireId(itemId, "itemId")
                requireId(pageId, "pageId")
            }
            KIND_BIBLE, KIND_BIBLE_TEXT -> {
                require(itemId != null && BibleAddress.isWire(itemId)) { "a Bible kind carries a wire" }
                require(pageId == null) { "a Bible kind carries no page id" }
            }
            else -> throw IllegalArgumentException("unknown kind $kind")
        }
        return "$VERSION$SEP$chrome$SEP$kind$SEP${itemId.orEmpty()}$SEP${pageId.orEmpty()}"
    }

    fun decode(payload: String): Decoded? {
        if (payload.length > MAX_PAYLOAD_CHARS) return null
        val parts = payload.split(SEP)
        if (parts.size != 5 || parts[0] != VERSION) return null
        val chrome = parts[1].toIntOrNull() ?: return null
        if (chrome != CHROME_NONE && chrome != CHROME_UNDERLINE) return null
        val kind = parts[2].toIntOrNull() ?: return null
        val itemId = parts[3].ifEmpty { null }
        val pageId = parts[4].ifEmpty { null }
        if (kind == KIND_BIBLE || kind == KIND_BIBLE_TEXT) {
            if (pageId != null || itemId == null || !BibleAddress.isWire(itemId)) return null
            return Decoded(chrome, kind, itemId = null, pageId = null, reference = itemId)
        }
        val ok = when (kind) {
            KIND_PAGE -> itemId == null && validId(pageId)
            KIND_ITEM -> pageId == null && validId(itemId)
            KIND_ITEM_PAGE -> validId(itemId) && validId(pageId)
            else -> false
        }
        return if (ok) Decoded(chrome, kind, itemId, pageId) else null
    }

    /** The wire a Bible payload names, either kind, or null for every other payload: the one
     *  predicate the screen asks to tell a Bible link from any other. */
    fun referenceOf(payload: String): String? = decode(payload)?.reference

    /** Whether [payload] is the verses on the page ([KIND_BIBLE_TEXT]), whose Edit is the text
     *  dialog rather than the reference dialog. */
    fun isBibleText(payload: String): Boolean = decode(payload)?.kind == KIND_BIBLE_TEXT

    /** The chrome a stored payload asks for; none when it cannot be read. */
    fun chromeOf(payload: String): Int = decode(payload)?.chrome ?: CHROME_NONE

    /** Truncate to the cap: what a row is written and read with. */
    fun cap(payload: String): String =
        if (payload.length <= MAX_PAYLOAD_CHARS) payload else payload.substring(0, MAX_PAYLOAD_CHARS)

    private fun validId(id: String?): Boolean = id != null && id.isNotBlank() && id.length <= MAX_ID_CHARS

    private fun requireId(id: String?, name: String) {
        require(!id.isNullOrBlank()) { "$name is blank" }
        require(id.length <= MAX_ID_CHARS) { "$name is too long" }
        require(!id.contains(SEP)) { "$name holds the separator" }
    }
}

/**
 * A link on a page: a lasso selection wrapped into one tappable thing. What it wraps keeps its
 * ids and its page-absolute coordinates; a wrap only re-parents the children page → link, and an
 * unlink puts them back. A link is never nested.
 *
 * [chrome] is decoded from [payload] once, at load or create, and never stored on its own. The
 * bounds are the union of the wrapped content plus the underline's clearance at the bottom.
 */
data class PageLink(
    val id: String,
    val payload: String,
    val chrome: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Z-order among the page's links. */
    val order: Int,
    /** Wrapped ink, page-absolute, in writing order. */
    val strokes: List<Stroke>,
    val headings: List<Heading> = emptyList(),
    val texts: List<PageText> = emptyList(),
    /** Wrapped sticky notes, icon only unless a caller read their content for a snapshot. */
    val stickies: List<PageSticky> = emptyList(),
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    /** Everything this link wraps. A wrapped sticky's content hangs under the sticky and is not here. */
    val childIds: List<String> get() =
        strokes.map { it.id } + headings.map { it.id } + texts.map { it.id } + stickies.map { it.id }

    /** The link and every wrapped child shift together; a sticky's content is in its own space. */
    fun translated(dx: Float, dy: Float): PageLink = copy(
        x = x + dx, y = y + dy,
        strokes = strokes.map { it.translated(dx, dy) },
        headings = headings.map { it.translated(dx, dy) },
        texts = texts.map { it.translated(dx, dy) },
        stickies = stickies.map { it.translated(dx, dy) },
    )

    /**
     * The wrapped headings and texts measured again for this device, and the box grown to hold
     * them. It only ever grows: a foreign file may wrap what this build cannot read, and a box
     * shrunk to what it can would cut the link down.
     */
    fun remeasured(measureHeading: (Heading) -> Heading, measureText: (PageText) -> PageText, density: Float): PageLink {
        if (headings.isEmpty() && texts.isEmpty()) return withUnderlineBand(density)
        val sized = copy(headings = headings.map(measureHeading), texts = texts.map(measureText))
        val b = unionBounds(sized.strokes, sized.headings, sized.texts, sized.stickies, density) ?: return sized
        return sized.copy(width = max(width, b.right - x), height = max(height, b.bottom - y))
    }

    /** A link written under a tighter band keeps drawing its underline against the ink otherwise. */
    fun withUnderlineBand(density: Float): PageLink {
        val b = unionBounds(strokes, headings, texts, stickies, density) ?: return this
        val needed = b.bottom - y
        return if (needed > height) copy(height = needed) else this
    }

    companion object {
        /** The clear space between the wrapped content's box and the underline, in dp. */
        const val UNDERLINE_CLEARANCE_DP = 4f

        /**
         * Where the underline sits: the lowest wrapped box plus the clearance. A heading, a text
         * and a sticky icon have a box of their own; loose ink is given one the same way, its
         * extent (the tight bounds of its points plus half its width) padded like a heading.
         */
        fun bandBottom(strokes: List<Stroke>, headings: List<Heading>, texts: List<PageText>, stickies: List<PageSticky>, density: Float): Float? {
            val pad = HeadingTypography.paddingPx(density)
            var box: Float? = null
            for (s in strokes) box = max(box ?: Float.NEGATIVE_INFINITY, s.bounds.bottom + s.width / 2f + pad)
            for (h in headings) box = max(box ?: Float.NEGATIVE_INFINITY, h.bounds.bottom)
            for (t in texts) box = max(box ?: Float.NEGATIVE_INFINITY, t.bounds.bottom)
            for (s in stickies) box = max(box ?: Float.NEGATIVE_INFINITY, s.bounds.bottom)
            return box?.plus(UNDERLINE_CLEARANCE_DP * density)
        }

        /** The union of the wrapped content's bounds, the bottom carried down to [bandBottom]. Null with nothing to wrap. */
        fun unionBounds(strokes: List<Stroke>, headings: List<Heading>, texts: List<PageText>, stickies: List<PageSticky>, density: Float): Bounds? {
            var union: Bounds? = null
            for (s in strokes) union = union?.union(s.bounds) ?: s.bounds
            for (h in headings) union = union?.union(h.bounds) ?: h.bounds
            for (t in texts) union = union?.union(t.bounds) ?: t.bounds
            for (s in stickies) union = union?.union(s.bounds) ?: s.bounds
            val b = union ?: return null
            val bottom = bandBottom(strokes, headings, texts, stickies, density) ?: b.bottom
            return Bounds(b.left, b.top, b.right, bottom)
        }
    }
}

/** The `link` row read back. The row shape is `NotebookSql.selectLinks`'s: `id, "order", text, x, y, width, height`. */
object LinkRows {

    /** Null when the row is not a usable link: the caller drops it and the page still shows. */
    fun toLink(row: Row, strokes: List<Stroke>, headings: List<Heading>, texts: List<PageText>, stickies: List<PageSticky>): PageLink? {
        return try {
            val x = row.realOrNull("x")?.toFloat() ?: return null
            val y = row.realOrNull("y")?.toFloat() ?: return null
            val w = row.realOrNull("width")?.toFloat() ?: return null
            val h = row.realOrNull("height")?.toFloat() ?: return null
            if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
            val payload = LinkPayload.cap(row.textOrNull("text") ?: "")
            PageLink(
                id = row.text("id"), payload = payload, chrome = LinkPayload.chromeOf(payload),
                x = x, y = y, width = w, height = h, order = row.long("order").toInt(),
                strokes = strokes, headings = headings, texts = texts, stickies = stickies,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * What a link points at, as the mirror table states it: the target item's id (its own, for a
 * page of this notebook) and the target page, or none for a whole item. Null when the payload
 * cannot be read: such a link has no mirror row, since it points nowhere Soil can name.
 */
data class LinkTarget(val itemId: String, val pageId: String?) {
    companion object {
        fun of(payload: String, ownItemId: String): LinkTarget? {
            val d = LinkPayload.decode(payload) ?: return null
            return when (d.kind) {
                LinkPayload.KIND_PAGE -> LinkTarget(ownItemId, d.pageId)
                LinkPayload.KIND_ITEM -> LinkTarget(d.itemId!!, null)
                LinkPayload.KIND_ITEM_PAGE -> LinkTarget(d.itemId!!, d.pageId)
                else -> null
            }
        }
    }
}

/**
 * What a tap on a link should do, before anything is checked: a hop within the open notebook, a
 * leave for another item, nothing (a link to the notebook it lives in), or a dead end (a payload
 * that cannot be read). Whether the target still exists is the screen's to ask, after planning
 * and before going. Plans carry ids, never indexes: the page list can change between the tap
 * and the hop.
 */
object LinkNav {

    sealed interface Follow {
        data class SamePage(val pageId: String) : Follow
        /** A null [pageId] opens the item at its own remembered page. */
        data class OtherItem(val itemId: String, val pageId: String?) : Follow
        /** A passage: Soil opens the Bible's reader on [wire]. */
        data class Bible(val wire: String) : Follow
        object Dead : Follow
        object NoOp : Follow
    }

    fun planFollow(payload: String, currentItemId: String): Follow {
        val d = LinkPayload.decode(payload) ?: return Follow.Dead
        // A wire the codec cannot read points nowhere the reader could go.
        d.reference?.let { return if (ReferenceCodec.decode(it) != null) Follow.Bible(it) else Follow.Dead }
        return when (d.kind) {
            LinkPayload.KIND_PAGE -> Follow.SamePage(d.pageId!!)
            LinkPayload.KIND_ITEM -> if (d.itemId == currentItemId) Follow.NoOp else Follow.OtherItem(d.itemId!!, null)
            LinkPayload.KIND_ITEM_PAGE -> if (d.itemId == currentItemId) Follow.SamePage(d.pageId!!) else Follow.OtherItem(d.itemId!!, d.pageId)
            else -> Follow.Dead
        }
    }

    sealed interface Back {
        data class SamePage(val pageId: String) : Back
        data class OtherItem(val itemId: String, val pageId: String) : Back
    }

    fun planBack(entryItemId: String, entryPageId: String, currentItemId: String): Back =
        if (entryItemId == currentItemId) Back.SamePage(entryPageId) else Back.OtherItem(entryItemId, entryPageId)
}

/** One hop the person came from: where a walk back lands. Ids only, never a name. */
data class TrailEntry(val itemId: String, val pageId: String)

/**
 * The trail's algebra: a bounded stack of [TrailEntry], newest last, and its stored form, one
 * `itemId/pageId` per line. What is stored is untrusted input: a line that does not read is
 * dropped, and a stored trail past the cap is cut to its newest.
 */
object TrailCodec {

    /** How deep a story goes before its oldest hop is forgotten; also the walk-back's bound. */
    const val MAX_ENTRIES = 50

    private const val SEP = '/'

    fun decode(raw: String?): List<TrailEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = raw.lineSequence().mapNotNull { line ->
            val at = line.indexOf(SEP)
            if (at <= 0 || at == line.lastIndex) null else TrailEntry(line.substring(0, at), line.substring(at + 1))
        }.toList()
        return if (out.size > MAX_ENTRIES) out.takeLast(MAX_ENTRIES) else out
    }

    fun encode(entries: List<TrailEntry>): String = entries.joinToString("\n") { "${it.itemId}$SEP${it.pageId}" }

    fun push(entries: List<TrailEntry>, entry: TrailEntry): List<TrailEntry> {
        val out = entries + entry
        return if (out.size > MAX_ENTRIES) out.takeLast(MAX_ENTRIES) else out
    }

    fun pop(entries: List<TrailEntry>): Pair<TrailEntry?, List<TrailEntry>> =
        if (entries.isEmpty()) null to entries else entries.last() to entries.dropLast(1)
}

/**
 * A page's name in the picker is its **topmost heading's** bare words, by `(y, x)`, or nothing,
 * in which case the card says "Page n". A wrapped heading counts: it is still the topmost thing
 * written on the page.
 */
object PageLabels {
    fun titleOf(headings: List<Heading>): String? =
        headings.minWithOrNull(compareBy({ it.y }, { it.x }))
            ?.let { HeadingPrefix.stripHeadingPrefix(it.text).trim().ifEmpty { null } }
}
