package com.symmetricalpalmtree.soil.paper.recognition

/** A recognition call that did not go through: what the seam refused it with. */
class RecognizerCallException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The model is not there yet: "still downloading", never an engine failure. */
    val notReady: Boolean get() = message == NOT_READY

    /** No recogniser is installed, or none is chosen in Soil's Settings. */
    val noRecognizer: Boolean get() = message == NO_RECOGNIZER

    /** Over the caps: too much ink at once. */
    val tooLarge: Boolean get() = message == TOO_LARGE

    companion object {
        const val NOT_READY = "recognizer not ready"
        const val NO_RECOGNIZER = "no recogniser"
        const val TOO_LARGE = "too much ink to recognise at once"
    }
}

/**
 * What the readiness flow and a recognition need of Soil, as an app provides it over the seam:
 * the status, the one call that may start a download, and the recognition itself. Every call
 * suspends off Main and throws [RecognizerCallException] for a refusal.
 */
interface RecognizerPort {
    /** One of [STATUS_READY], [STATUS_NEEDS_DOWNLOAD], [STATUS_DOWNLOADING], [STATUS_UNAVAILABLE]. */
    suspend fun status(): Int
    suspend fun prepare()

    companion object {
        const val STATUS_READY = 0
        const val STATUS_NEEDS_DOWNLOAD = 1
        const val STATUS_DOWNLOADING = 2
        const val STATUS_UNAVAILABLE = 3
    }
}
