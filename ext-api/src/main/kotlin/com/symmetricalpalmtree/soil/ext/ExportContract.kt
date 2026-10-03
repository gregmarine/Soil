package com.symmetricalpalmtree.soil.ext

/**
 * The export and import points: what an exporter or importer `<service>` declares, the caps its
 * descriptors are held to, and the option ids Soil recognises and acts on itself.
 *
 * Soil keys, the extension delivers. Option values cross as a plain id → value map
 * ([ExportSpec.values]); the reserved [OPTION_KEYING] is executed by Soil (its transform runs in
 * Soil, and a typed passphrase never enters the spec), [OPTION_PAGE_TEMPLATE] by Soil's render,
 * and [OPTION_PROTECT] by the extension with a secret Soil collects and hands over on
 * [ExportSpec.exportSecret].
 */
object ExportContract {

    /** The action an exporter `<service>` answers. */
    const val ACTION_EXPORTER = "com.symmetricalpalmtree.soil.ext.EXPORTER"

    /** The action an importer `<service>` answers. */
    const val ACTION_IMPORTER = "com.symmetricalpalmtree.soil.ext.IMPORTER"

    // ── Caps, enforced by the parcelable constructors: unmarshal is validation ──────

    const val MAX_OPTIONS = 8
    const val MAX_CHOICES = 8
    const val MAX_ID_CHARS = 32
    const val MAX_LABEL_CHARS = 80
    const val MAX_FILE_EXTENSION_CHARS = 12
    const val MAX_MIME_CHARS = 128
    const val MAX_SPEC_VALUE_CHARS = 64
    const val MAX_NAME_CHARS = 200
    const val MAX_EXPORT_SECRET_CHARS = 128
    const val MAX_FILE_EXTENSIONS = 8
    const val MAX_MIME_TYPES = 8

    // ── Option kinds ──────

    /** One of a bounded list of choices; the chosen choice id crosses. */
    const val KIND_SINGLE_CHOICE = 0

    /** On or off; `"1"` or `"0"` crosses. */
    const val KIND_TOGGLE = 1

    /** A secret Soil collects and consumes itself. No entry ever crosses in the spec. */
    const val KIND_PASSPHRASE = 2

    // ── Source kinds: what the read fd carries ──────

    /** The prepared item file, streamed verbatim. The default. */
    const val SOURCE_FILE = 0

    /** A [PageBundle] of host-rendered pages, for a format that never sees the file itself. */
    const val SOURCE_PAGES = 1

    // ── Delivery: how many files one export produces ──────

    /** One export, one file. The default. */
    const val DELIVERY_ONE_FILE = 0

    /** One file per page, legal only with [SOURCE_PAGES]: Soil splits the bundle and calls the
     *  exporter once per page with a fresh destination in a folder the person picked. The
     *  exporter must refuse a bundle of more than one page, never write the first silently. */
    const val DELIVERY_PER_PAGE = 1

    // ── Reserved option ids ──────

    /** Single-choice, executed by Soil: how the exported file is keyed. */
    const val OPTION_KEYING = "keying"

    /** Keep encrypted under this device's key: a pure copy. */
    const val KEYING_KEEP = "keep"

    /** Re-key to a passphrase typed for this file alone. */
    const val KEYING_REKEY = "rekey"

    /** Remove encryption: plaintext output. */
    const val KEYING_PLAIN = "plain"

    /** Toggle, executed by Soil's render: `"1"` bakes each page's paper under its ink, `"0"` a
     *  white ground. The value still crosses so the extension knows what was asked. */
    const val OPTION_PAGE_TEMPLATE = "template"

    /** Toggle: `"1"` makes Soil collect a password with its own fields and send it on
     *  [ExportSpec.exportSecret]. The protection is the extension's work. */
    const val OPTION_PROTECT = "protect"

    /**
     * Single-choice, executed by Soil as well as by the exporter: which image a page becomes.
     * The exporter encodes it; Soil names the file and the picker's type after the choice
     * ([imageExtension], [imageMime]), since one descriptor carries one extension. An exporter
     * declaring it must use only the known choice ids.
     */
    const val OPTION_IMAGE_FORMAT = "format"
    const val IMAGE_FORMAT_PNG = "png"
    const val IMAGE_FORMAT_JPEG = "jpeg"
    const val IMAGE_FORMAT_WEBP = "webp"

    /** Single-choice, the exporter's own: how hard a lossy format compresses. */
    const val OPTION_QUALITY = "quality"
    const val QUALITY_BEST = "best"
    const val QUALITY_BALANCED = "balanced"
    const val QUALITY_SMALL = "small"

    /** The file extension for an image-format choice, or null for one Soil does not know. */
    fun imageExtension(format: String): String? = when (format) {
        IMAGE_FORMAT_PNG -> "png"
        IMAGE_FORMAT_JPEG -> "jpg"
        IMAGE_FORMAT_WEBP -> "webp"
        else -> null
    }

    /** The MIME type for an image-format choice, or null for one Soil does not know. */
    fun imageMime(format: String): String? = when (format) {
        IMAGE_FORMAT_PNG -> "image/png"
        IMAGE_FORMAT_JPEG -> "image/jpeg"
        IMAGE_FORMAT_WEBP -> "image/webp"
        else -> null
    }

    // ── Timeouts, Soil's side ──────

    const val DESCRIBE_TIMEOUT_MS = 3_000L

    /** One value for both source kinds. Measured on the Nomad in Notesprout SN: a 100 MB copy in
     *  under a second, a 13-page PDF assembly in under three. Two minutes covers a 1 GB file
     *  through a slow provider and a notebook of hundreds of pages. Soil's render runs before
     *  the call and never counts against it. */
    const val EXPORT_TIMEOUT_MS = 120_000L
    const val IMPORT_TIMEOUT_MS = EXPORT_TIMEOUT_MS

    internal fun requireId(value: String, what: String) {
        require(value.isNotEmpty() && value.length <= MAX_ID_CHARS) { "$what: length ${value.length} outside 1..$MAX_ID_CHARS" }
        require(value.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) { "$what: '$value' is not [A-Za-z0-9_-]+" }
    }

    internal fun requireLabel(value: String, what: String) {
        require(value.isNotBlank() && value.length <= MAX_LABEL_CHARS) { "$what: blank or over $MAX_LABEL_CHARS chars" }
    }

    internal fun requireExtension(ext: String) {
        require(ext.isNotEmpty() && ext.length <= MAX_FILE_EXTENSION_CHARS && ext.all { it in 'a'..'z' || it in '0'..'9' }) {
            "file extension '$ext' is not [a-z0-9]{1..$MAX_FILE_EXTENSION_CHARS}"
        }
    }

    internal fun requireMime(mime: String) {
        require(mime.length in 3..MAX_MIME_CHARS && mime.count { it == '/' } == 1 && !mime.startsWith('/') && !mime.endsWith('/')) {
            "malformed MIME type '$mime'"
        }
    }

    internal fun requireValues(values: Map<String, String>) {
        require(values.size <= MAX_OPTIONS) { "${values.size} spec entries > $MAX_OPTIONS" }
        for ((key, value) in values) {
            requireId(key, "spec key")
            require(value.length <= MAX_SPEC_VALUE_CHARS) { "spec value for '$key' over $MAX_SPEC_VALUE_CHARS chars" }
        }
    }

    internal fun requireDisplayName(name: String, what: String) {
        require(name.length <= MAX_NAME_CHARS) { "$what over $MAX_NAME_CHARS chars" }
        require('/' !in name && '\u0000' !in name) { "$what is a display name, never a path" }
    }
}
