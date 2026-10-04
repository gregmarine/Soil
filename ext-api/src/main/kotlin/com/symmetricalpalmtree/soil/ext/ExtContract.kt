package com.symmetricalpalmtree.soil.ext

/**
 * The constants both sides of an extension compile against. An extension is a `<service>` that
 * answers one of the actions here, declares [META_API_VERSION], and is signed with Soil's key;
 * Soil binds it on an app's behalf and the app never sees it.
 */
object ExtContract {

    /** The contract's version. An extension declares the version it was built against. */
    const val API_VERSION = 1

    /** The `<service>` meta-data naming the contract version the extension was built against. */
    const val META_API_VERSION = "com.symmetricalpalmtree.soil.ext.API_VERSION"

    // ── The recogniser ──────

    /** The action a recogniser `<service>` answers. */
    const val ACTION_RECOGNIZER = "com.symmetricalpalmtree.soil.ext.RECOGNIZER"

    /** The `<service>` meta-data listing the language tags the recogniser can take, comma-separated. */
    const val META_LANGUAGES = "com.symmetricalpalmtree.soil.ext.LANGUAGES"

    /** `IRecognizer.status`: the model is on the device and the engine built. */
    const val STATUS_READY = 0

    /** The model is not on the device: call `prepare`. */
    const val STATUS_NEEDS_DOWNLOAD = 1

    /** `prepare` started and the download is in flight. */
    const val STATUS_DOWNLOADING = 2

    /** The engine cannot run here. Anything outside `0..3` reads as this. */
    const val STATUS_UNAVAILABLE = 3

    /** Most strokes in one recognise call. */
    const val MAX_INK_STROKES = 2_000

    /** Most points summed over one call's strokes. */
    const val MAX_INK_POINTS = 60_000

    /** The tail of `preContext` that crosses. */
    const val MAX_PRECONTEXT_CHARS = 20

    /** The most text a recognise call answers; the rest is dropped. */
    const val MAX_RECOGNIZED_CHARS = 20_000

    /** A language tag's length. */
    const val MAX_LANGUAGE_CHARS = 32

    /**
     * The exact message of the `IllegalStateException` a recogniser throws when it could not
     * become ready within the call. Compared verbatim: that one case reads as "still downloading".
     */
    const val NOT_READY = "recognizer not ready"

    /** Whether [raw] is a status this contract knows. */
    fun status(raw: Int): Int = if (raw in STATUS_READY..STATUS_UNAVAILABLE) raw else STATUS_UNAVAILABLE

    /** The language tags a service declared, trimmed, in order, empties dropped. */
    fun languages(declared: String?): List<String> =
        declared.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_LANGUAGE_CHARS }

    /** Soil's debug build and release build share one key: an extension serves the build it was built for. */
    fun sameBuild(hostPackage: String, extensionPackage: String): Boolean =
        hostPackage.endsWith(DEV_SUFFIX) == extensionPackage.endsWith(DEV_SUFFIX)

    private const val DEV_SUFFIX = ".dev"
}
