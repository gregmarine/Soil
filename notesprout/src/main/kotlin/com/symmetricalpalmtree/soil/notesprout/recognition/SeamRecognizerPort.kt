package com.symmetricalpalmtree.soil.notesprout.recognition

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerCallException
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerPort
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recognition as Soil relays it: the status and the download start behind the consent flow, and
 * the recognition of one writing area. Every call crosses on IO; every refusal becomes a
 * [RecognizerCallException] carrying the seam's exact message, so the flow can tell "not ready"
 * and "no recogniser" from a failure.
 */
class SeamRecognizerPort(private val seam: suspend () -> ISoilSeam) : RecognizerPort {

    override suspend fun status(): Int = call { it.recognizerStatus() }

    override suspend fun prepare() = call { it.prepareRecognizer() }

    /** The text of [strokes], in writing order, read in reading order by the recogniser. */
    suspend fun recognizeInk(strokes: List<Stroke>, areaWidth: Float, areaHeight: Float, preContext: String): String =
        call { it.recognizeInk(SeamShared.write(InkWire.encode(strokes, areaWidth, areaHeight)), areaWidth, areaHeight, preContext) }

    private suspend fun <T> call(block: (ISoilSeam) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(seam())
        } catch (e: SeamUnavailable) {
            throw RecognizerCallException(SeamLimits.RECOGNITION_FAILED, e)
        } catch (e: IllegalStateException) {
            throw RecognizerCallException(e.message ?: SeamLimits.RECOGNITION_FAILED, e)
        } catch (e: IllegalArgumentException) {
            throw RecognizerCallException(SeamLimits.INK_TOO_LARGE, e)
        } catch (e: SecurityException) {
            throw RecognizerCallException(SeamLimits.RECOGNITION_FAILED, e)
        } catch (e: android.os.RemoteException) {
            throw RecognizerCallException(SeamLimits.RECOGNITION_FAILED, e)
        }
    }
}
