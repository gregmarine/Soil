package com.symmetricalpalmtree.soil.paper.templates

import java.security.MessageDigest

/**
 * **What a page may have under its ink.** Two kinds and no third: a **built-in** is paper the app
 * draws from arithmetic ([TemplateKind], [TemplateGeometry]); a **picture** is pixels the library
 * keeps, laid onto the page under a [TemplateFit]. Blank is the absence of either.
 *
 * Everything in this file is pure Kotlin and JVM-tested: the geometry that is baked into every
 * notebook, the token a notebook knows its paper by, the fit arithmetic, and the import's sizes.
 * The Android half, which holds a brush, is [BuiltInTemplates] and [PagePaper].
 */

/** The four built-in page backgrounds. [BLANK] writes no template row at all. */
enum class TemplateKind { BLANK, LINED, DOTTED, GRID }

/**
 * Where the rules, dots and grid lines go. Everything derives from one physical constant, **8 mm**
 * between features, at the panel's real dpi: paper is measured in millimetres, so a template looks
 * the same size on any device. Feature sizes are authored at mdpi and scaled by dpi; a literal 1 px
 * rule on a 300 ppi panel is faint grey, not a line.
 *
 * Two origins, on purpose: lines start at **2 × spacing** (a writing sheet wants a top margin);
 * the grid and the dots start at **1 × spacing** and are symmetric in both axes.
 */
object TemplateGeometry {

    const val SPACING_MM = 8f
    private const val LINE_WIDTH_MDPI = 1f
    private const val DOT_RADIUS_MDPI = 2f

    fun spacingPx(dpi: Float): Float = SPACING_MM * dpi / 25.4f

    /** 1 px at mdpi, never thinner than 1 px (about 2 px on a 300 ppi panel). */
    fun lineWidthPx(dpi: Float): Float = maxOf(1f, LINE_WIDTH_MDPI * dpi / 160f)

    /** 2 px at mdpi, never smaller than 1 px: a 1.5 px dot read as faint grey on e-ink. */
    fun dotRadiusPx(dpi: Float): Float = maxOf(1f, DOT_RADIUS_MDPI * dpi / 160f)

    fun linePositions(heightPx: Int, spacingPx: Float): List<Float> = steps(spacingPx * 2f, heightPx, spacingPx)
    fun gridPositionsX(widthPx: Int, spacingPx: Float): List<Float> = steps(spacingPx, widthPx, spacingPx)
    fun gridPositionsY(heightPx: Int, spacingPx: Float): List<Float> = steps(spacingPx, heightPx, spacingPx)

    /** The intersections of the grid, row-major. */
    fun dotPositions(widthPx: Int, heightPx: Int, spacingPx: Float): List<Pair<Float, Float>> {
        val xs = gridPositionsX(widthPx, spacingPx)
        val out = ArrayList<Pair<Float, Float>>(xs.size * 8)
        for (y in gridPositionsY(heightPx, spacingPx)) for (x in xs) out.add(x to y)
        return out
    }

    private fun steps(from: Float, limit: Int, step: Float): List<Float> {
        if (step <= 0f) return emptyList()
        val out = mutableListOf<Float>()
        var v = from
        while (v < limit) {
            out.add(v)
            v += step
        }
        return out
    }
}

/**
 * How a picture becomes a page: [FIT] the whole picture centred on white, aspect kept; [STRETCH]
 * pulled to the corners; [FILL] scaled to cover, the overhang cropped evenly. One source rect and
 * one destination rect: a blit of `src → dst` onto a white page is the whole render.
 */
object TemplateFit {

    const val FIT = 0
    const val STRETCH = 1
    const val FILL = 2

    val MODES: List<Int> = listOf(FIT, STRETCH, FILL)

    /** A mode read out of a row, clamped to one this build can draw. */
    fun sanitize(fit: Int?): Int = if (fit in MODES) fit!! else FIT

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class Plan(val src: Rect, val dst: Rect)

    /** Null when either size is degenerate: there is nothing to draw. */
    fun plan(fit: Int, srcWidth: Int, srcHeight: Int, pageWidth: Int, pageHeight: Int): Plan? {
        if (srcWidth <= 0 || srcHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) return null
        val sw = srcWidth.toFloat()
        val sh = srcHeight.toFloat()
        val pw = pageWidth.toFloat()
        val ph = pageHeight.toFloat()
        val whole = Rect(0f, 0f, sw, sh)
        val page = Rect(0f, 0f, pw, ph)
        return when (sanitize(fit)) {
            STRETCH -> Plan(whole, page)
            FILL -> {
                val scale = maxOf(pw / sw, ph / sh)
                val keepW = minOf(sw, pw / scale)
                val keepH = minOf(sh, ph / scale)
                val left = (sw - keepW) / 2f
                val top = (sh - keepH) / 2f
                Plan(Rect(left, top, left + keepW, top + keepH), page)
            }
            else -> {
                val scale = minOf(pw / sw, ph / sh)
                val w = sw * scale
                val h = sh * scale
                val left = (pw - w) / 2f
                val top = (ph - h) / 2f
                Plan(whole, Rect(left, top, left + w, top + h))
            }
        }
    }
}

/**
 * The notebook file's `template` row **token**: the whole of what "this is the same paper" means
 * inside a file. `""` is blank (no row at all); `LINED` / `DOTTED` / `GRID` are the built-ins as
 * every SN file spelled them; `IMG#<8 hex>` is a picture, named by a digest of **its fit and its
 * bytes**: the same picture fitted and stretched are two papers, and the fit byte goes first so it
 * can never be read as image data.
 */
object TemplateToken {

    const val IMAGE_PREFIX = "IMG#"
    const val IMAGE_DIGEST_CHARS = 8

    fun of(kind: TemplateKind): String = if (kind == TemplateKind.BLANK) "" else kind.name

    fun ofImage(bytes: ByteArray, fit: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf((fit and 0xFF).toByte()))
        digest.update(bytes)
        val hash = digest.digest()
        val hex = StringBuilder(IMAGE_DIGEST_CHARS)
        for (i in 0 until IMAGE_DIGEST_CHARS / 2) hex.append("%02x".format(hash[i]))
        return IMAGE_PREFIX + hex
    }

    fun isImage(token: String): Boolean =
        token.startsWith(IMAGE_PREFIX) && token.length == IMAGE_PREFIX.length + IMAGE_DIGEST_CHARS

    /** The built-in [token] names, or null for a picture or paper authored by a later build. `""` is blank. */
    fun kindOf(token: String): TemplateKind? {
        if (token.isEmpty()) return TemplateKind.BLANK
        return TemplateKind.entries.firstOrNull { it != TemplateKind.BLANK && it.name == token }
    }
}

/**
 * What a picture survives before it may become a template row. Downscaled to the page's long
 * edge, never upscaled, and refused above [MAX_BLOB_BYTES]: SQLCipher reads a row through an
 * 8 MiB window, and a blob past it writes fine and then cannot be read back.
 */
object TemplateImport {

    const val MAX_BLOB_BYTES: Int = 6 * 1024 * 1024

    val MIME_TYPES: Array<String> = arrayOf("image/png", "image/jpeg", "image/webp")

    /** The largest power-of-two sample that leaves the long edge at or above [maxEdge]. */
    fun sampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return 1
        val longEdge = maxOf(srcWidth, srcHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** The size to store at, or null when the picture is already small enough: leave it alone. */
    fun scaledSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int>? {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return null
        val longEdge = maxOf(srcWidth, srcHeight)
        if (longEdge <= maxEdge) return null
        val scale = maxEdge.toDouble() / longEdge
        val w = Math.round(srcWidth * scale).toInt().coerceAtLeast(1)
        val h = Math.round(srcHeight * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    fun overCap(encodedBytes: Int): Boolean = encodedBytes > MAX_BLOB_BYTES

    /** `6.0 MB`, in the units a file manager uses. */
    fun megabytes(bytes: Int): String = "%.1f MB".format(bytes / 1_000_000.0)

    /** The name to offer for a file: its basename without the extension, cleaned to the name
     *  charset with runs of spaces collapsed; [fallback] when nothing usable is left. */
    fun nameFrom(displayName: String?, fallback: String): String {
        val base = displayName.orEmpty().substringAfterLast('/').substringBeforeLast('.')
        val cleaned = TemplateNames.reduce(base)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") fallback else cleaned
    }
}

/**
 * What a template or a template folder may be called: the family charset, so a name is always
 * safe in an export filename. Uniqueness among siblings is the store's question.
 */
object TemplateNames {

    val CHARSET = Regex("^[a-zA-Z0-9_\\-. ]*$")
    private val OUTSIDE_CHARSET = Regex("[^a-zA-Z0-9_\\-. ]")
    private val SPACES = Regex(" {2,}")

    const val MAX_CHARS = 60

    enum class Problem { EMPTY, RESERVED, CHARSET, TOO_LONG }

    /** Null when [name] (already trimmed) is acceptable. */
    fun validate(name: String): Problem? = when {
        name.isBlank() -> Problem.EMPTY
        name == "." || name == ".." -> Problem.RESERVED
        !CHARSET.matches(name) -> Problem.CHARSET
        name.length > MAX_CHARS -> Problem.TOO_LONG
        else -> null
    }

    /** [text] with every character outside the charset dropped, spaces collapsed, trimmed and capped. */
    fun reduce(text: String): String =
        SPACES.replace(OUTSIDE_CHARSET.replace(text, " "), " ").trim().take(MAX_CHARS).trim()

    /** The name a page seeds for Save as template: its title reduced, else "page N". Never empty. */
    fun seedFor(pageTitle: String?, pageNumber: Int): String {
        val title = pageTitle?.let { reduce(it) }
        if (!title.isNullOrEmpty() && validate(title) == null) return title
        return if (pageNumber >= 1) "page $pageNumber" else "page"
    }

    /** `"Ruled"` → `"Ruled copy"` → `"Ruled copy 2"` …, skipping every name in [taken]. Bounded. */
    fun duplicateName(base: String, taken: Set<String>): String {
        val first = "$base copy"
        if (first !in taken) return first
        var n = 2
        var candidate = "$first $n"
        while (candidate in taken && n < 1000) {
            n++
            candidate = "$first $n"
        }
        return candidate
    }
}

/**
 * What a tap on a template card means, and the whole of what crosses between the picker and the
 * app that asked: a **card**, never pixels. [Blank] is no paper; [BuiltIn] the app's own
 * arithmetic; [Static] a library row by id, which the app resolves through the seam.
 */
sealed class TemplatePick {

    object Blank : TemplatePick()
    data class BuiltIn(val kind: TemplateKind) : TemplatePick()
    data class Static(val id: String) : TemplatePick()

    /** The card this pick stands for: a sentinel for the first two, the row's id for the third. */
    val cardId: String
        get() = when (this) {
            Blank -> TemplateIds.BLANK
            is BuiltIn -> TemplateIds.ofKind(kind) ?: TemplateIds.BLANK
            is Static -> id
        }

    fun encode(): String = when (this) {
        Blank -> BLANK
        is BuiltIn -> KIND_PREFIX + kind.name
        is Static -> STATIC_PREFIX + id
    }

    companion object {
        private const val BLANK = "blank"
        private const val KIND_PREFIX = "kind:"
        private const val STATIC_PREFIX = "static:"

        /** Null for anything this build cannot read: the same answer as a cancel, never Blank. */
        fun decode(encoded: String?): TemplatePick? = when {
            encoded.isNullOrEmpty() -> null
            encoded == BLANK -> Blank
            encoded.startsWith(KIND_PREFIX) -> encoded.removePrefix(KIND_PREFIX)
                .let { name -> TemplateKind.entries.firstOrNull { it.name == name } }
                ?.let { if (it == TemplateKind.BLANK) Blank else BuiltIn(it) }
            encoded.startsWith(STATIC_PREFIX) -> encoded.removePrefix(STATIC_PREFIX).takeIf { it.isNotEmpty() }?.let { Static(it) }
            else -> null
        }
    }
}

/**
 * The sentinels: Blank, the Default folder and the three built-in papers are hardcoded ids
 * composed into every listing, never rows. Nothing is seeded, nothing can be deleted or renamed,
 * and a library restored from a backup needs no repair.
 */
object TemplateIds {
    private const val PREFIX = "00000000-0000-4000-8000-0000"

    const val BLANK = PREFIX + "5f626c616e6b"      // _blank
    const val DEFAULT_FOLDER = PREFIX + "6465666c745f"   // deflt_
    const val LINED = PREFIX + "6c696e65645f"      // lined_
    const val DOTTED = PREFIX + "646f74746564"     // dotted
    const val GRID = PREFIX + "5f677269645f"       // _grid_

    /** The three built-in papers, in the order they always appear inside Default. */
    val BUILT_INS: List<Pair<String, TemplateKind>> = listOf(LINED to TemplateKind.LINED, DOTTED to TemplateKind.DOTTED, GRID to TemplateKind.GRID)

    val SENTINELS: Set<String> = setOf(BLANK, DEFAULT_FOLDER, LINED, DOTTED, GRID)

    fun isSentinel(id: String): Boolean = id in SENTINELS

    fun ofKind(kind: TemplateKind): String? = BUILT_INS.firstOrNull { it.second == kind }?.first

    fun kindOf(id: String): TemplateKind? = BUILT_INS.firstOrNull { it.first == id }?.second

    /** The reserved folder name at the root, compared case-insensitively. Deeper, it is ordinary. */
    const val RESERVED_ROOT_NAME = "Default"

    fun isReservedName(parentId: String, name: String): Boolean =
        parentId.isEmpty() && name.trim().equals(RESERVED_ROOT_NAME, ignoreCase = true)
}

/** A template row of a notebook file, blob-free: what the reuse rule reads. */
data class TemplateDigest(val id: String, val token: String?, val width: Float?, val height: Float?, val blobLength: Long?)

/**
 * Which template row a page is pointed at when it is re-papered: **reuse before mint**. A row is
 * shared paper, not a page's property, so a row this file already holds that is the wanted paper
 * at the page's exact size is pointed at, and only otherwise is another render stored. Identity is
 * `token + page size`, never pixels. [prefer], the page's own row, wins among equals, which is
 * what makes re-picking the ticked card a true no-op.
 */
object PageTemplate {

    fun reusableId(digests: List<TemplateDigest>, token: String, widthPx: Int, heightPx: Int, prefer: String? = null): String? {
        if (token.isEmpty()) return null
        val matches = digests.filter { d ->
            d.token == token && (d.blobLength ?: 0) > 0 &&
                (d.width ?: 0f).toInt() == widthPx && (d.height ?: 0f).toInt() == heightPx
        }
        return matches.firstOrNull { it.id == prefer }?.id ?: matches.firstOrNull()?.id
    }

    /** The token [templateId]'s row carries; `""` for blank; null when the row has gone, which ticks nothing. */
    fun tokenOf(digests: List<TemplateDigest>, templateId: String): String? {
        if (templateId.isEmpty()) return ""
        return digests.firstOrNull { it.id == templateId }?.token
    }
}
