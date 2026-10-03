package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Ink crossing between the Scratch Pad and a notebook: a page size and a set of strokes as one
 * byte document, pure and shared by both ends. Big-endian: magic `SLIK` · u8 version 1 · f32
 * width · f32 height · u32 count · per stroke f32 width · i32 colour · u16 style-name length +
 * UTF-8 · u32 blob length + the stroke's points as `StrokeCodec` format B.
 *
 * **No id crosses**: [decode] mints fresh ones. Nothing from the wire is trusted beyond its
 * geometry: an unknown style reads as PEN, the width is clamped, a point-less stroke is skipped,
 * and a document over the caps, truncated or malformed reads as nothing at all. Coordinates are
 * kept 1:1: the pad page and the notebook page are both this device's screen.
 */
object InkWire {

    const val MAX_STROKES = 10_000
    const val MAX_POINTS = 400_000
    const val MIN_WIDTH = 0.5f
    const val MAX_WIDTH = 50f

    private const val MAGIC = "SLIK"
    private const val VERSION = 1

    class Bundle(val pageWidth: Float, val pageHeight: Float, val strokes: List<Stroke>)

    fun pointCount(strokes: List<Stroke>): Int = strokes.sumOf { it.points.size }

    /** Whether [strokes] may be sent at all. Over the caps is a refused send, never a cut one. */
    fun withinLimits(strokes: List<Stroke>): Boolean = strokes.size <= MAX_STROKES && pointCount(strokes) <= MAX_POINTS

    fun encode(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float): ByteArray {
        val sent = strokes.filter { it.points.isNotEmpty() }
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeBytes(MAGIC)
        out.writeByte(VERSION)
        out.writeFloat(pageWidth)
        out.writeFloat(pageHeight)
        out.writeInt(sent.size)
        for (s in sent) {
            out.writeFloat(s.width)
            out.writeInt(s.color)
            val name = s.style.name.toByteArray(Charsets.UTF_8)
            out.writeShort(name.size)
            out.write(name)
            val blob = StrokeBlob.encode(s)
            out.writeInt(blob.size)
            out.write(blob)
        }
        out.flush()
        return bytes.toByteArray()
    }

    /** The bundle in [bytes], or null for anything unusable. */
    fun decode(bytes: ByteArray?, newId: () -> String = { UUID.randomUUID().toString() }): Bundle? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val m = ByteArray(4)
            input.readFully(m)
            if (String(m, Charsets.US_ASCII) != MAGIC) return null
            if (input.readUnsignedByte() != VERSION) return null
            val w = input.readFloat()
            val h = input.readFloat()
            if (!w.isFinite() || !h.isFinite() || w < 0f || h < 0f) return null
            val n = input.readInt()
            if (n < 0 || n > MAX_STROKES) return null
            val strokes = ArrayList<Stroke>(n)
            var points = 0
            repeat(n) {
                val width = input.readFloat()
                val color = input.readInt()
                val nameLen = input.readUnsignedShort()
                val name = ByteArray(nameLen).also { input.readFully(it) }
                val blobLen = input.readInt()
                if (blobLen < 0 || blobLen > input.available()) return null
                val blob = ByteArray(blobLen).also { input.readFully(it) }
                val decoded = StrokeCodec.decode(blob)
                if (decoded.size == 0) return@repeat
                points += decoded.size
                if (points > MAX_POINTS) return null
                val list = ArrayList<StrokePoint>(decoded.size)
                for (i in 0 until decoded.size) {
                    list += StrokePoint(decoded.x[i], decoded.y[i], decoded.pressure?.get(i) ?: 1f, decoded.tilt?.get(i) ?: 0f, 0L)
                }
                val safeWidth = if (width.isFinite()) width.coerceIn(MIN_WIDTH, MAX_WIDTH) else MIN_WIDTH
                strokes += Stroke(id = newId(), points = list, color = color, width = safeWidth, style = StrokeRows.styleOf(String(name, Charsets.UTF_8)))
            }
            if (input.available() != 0) return null
            Bundle(w, h, strokes)
        } catch (_: Exception) {
            null
        }
    }
}
