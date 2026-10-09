package com.symmetricalpalmtree.soil.paper.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Round-trips plus the **byte-compat proof**: the `PAPER_*` fixtures below were produced by
 * Paper's `StrokeCodec.encode` (generated 2026-08-20 from the tree at `main`, deterministic
 * inputs re-created by [fixtureInputs]). SN must decode them to the exact same geometry, and
 * SN's encode must produce the identical *decompressed* wire payload (version byte + inflated
 * bytes — deflate output may legally differ across zlib builds, the payload may not).
 */
class StrokeCodecTest {

    // ── Fixtures from Paper's codec ──────────────────────────────────────────

    private val paperFixtures = mapOf(
        Triple(17, true, true) to "AXjaNY0hCgJBGEY/WDAZxGRRNphMNpPu+uMFBLMX8ALGCZ5D5gAewOJq8ABiNmw0GD2Ab5jfhQ/em+XNFFK5lCILC0lXaQOf2A1vG+kAn5kqzvAjfGclHpv87+F9wJ/wy/sa/8Bv74UXJn29Dxeph8v8fb4h3LHch7k0gbvmPWczuG+5r/EVPPA+bQ2PvI9sC4+9T2c7y3emvsX38PT/fvUD39ovWg==",
        Triple(9, true, false) to "AXjaY2RgUHBkYFgAxA12DAwhQHoNEB8AsluA9A4gZrBnYJgHpI8AsYI9ROwCVP0lIH0Hqv4NkH4BVc/sxMDwBapeAMhmcAKpBwBt6xR4",
        Triple(5, false, true) to "AXjaY2JgUHBkYFgAxAz7GRhCgPQaIH6wj4GhBUjvAOIDQPY8IH0EpG4fROwCEDfsAwCNxw7q",
        Triple(12, false, false) to "AXjaY2BgUHBkYFgAxCFAvAaIW4B4BxDPA+IjUPYFIL4ExHeA+A0QvwBiZicGhi9AWgBIMwCxLBCzAbEWEPMAsTkQCzkBAJ9JETM=",
        Triple(1, true, true) to "AXjaY2ZgUHBkYFgAxA12DAwM+wESzwLD",
        Triple(0, true, true) to "AXjaYwYAAAQABA==",
    )

    /** The deterministic inputs the fixtures were generated from (same formulas as the generator). */
    private fun fixtureInputs(n: Int, pressure: Boolean, tilt: Boolean): Array<FloatArray?> {
        val x = FloatArray(n) { 10f + it * 3.25f }
        val y = FloatArray(n) { 20f + it * 1.5f }
        val p = if (pressure) FloatArray(n) { 0.25f + (it % 4) * 0.125f } else null
        val t = if (tilt) FloatArray(n) { -0.5f + it * 0.0625f } else null
        return arrayOf(x, y, p, t)
    }

    @Test
    fun decodesPaperFixtures_exactGeometry() {
        for ((shape, b64) in paperFixtures) {
            val (n, hasP, hasT) = shape
            val (x, y, p, t) = fixtureInputs(n, hasP, hasT)
            val pts = StrokeCodec.decode(Base64.getDecoder().decode(b64))
            assertEquals("count for $shape", n, pts.size)
            assertArrayEquals("x for $shape", x, pts.x, 0f)
            assertArrayEquals("y for $shape", y, pts.y, 0f)
            if (hasP) assertArrayEquals("pressure for $shape", p, pts.pressure, 0f) else assertNull(pts.pressure)
            if (hasT) assertArrayEquals("tilt for $shape", t, pts.tilt, 0f) else assertNull(pts.tilt)
        }
    }

    @Test
    fun encodeMatchesPaperWirePayload_byteForByte() {
        for ((shape, b64) in paperFixtures) {
            val (n, hasP, hasT) = shape
            val (x, y, p, t) = fixtureInputs(n, hasP, hasT)
            val ours = StrokeCodec.encode(x!!, y!!, p, t)
            val paper = Base64.getDecoder().decode(b64)
            assertEquals("version byte for $shape", paper[0], ours[0])
            assertArrayEquals("decompressed payload for $shape", inflate(paper), inflate(ours))
        }
    }

    @Test
    fun roundTrips_allChannelCombinations() {
        for (hasP in listOf(true, false)) for (hasT in listOf(true, false)) {
            val n = 33
            val x = FloatArray(n) { it * 0.7f }
            val y = FloatArray(n) { 1000f - it * 2.3f }
            val p = if (hasP) FloatArray(n) { (it % 10) / 10f } else null
            val t = if (hasT) FloatArray(n) { it * 0.01f } else null
            val pts = StrokeCodec.decode(StrokeCodec.encode(x, y, p, t))
            assertEquals(n, pts.size)
            assertArrayEquals(x, pts.x, 0f)
            assertArrayEquals(y, pts.y, 0f)
            if (hasP) assertArrayEquals(p, pts.pressure, 0f) else assertNull(pts.pressure)
            if (hasT) assertArrayEquals(t, pts.tilt, 0f) else assertNull(pts.tilt)
        }
    }

    @Test
    fun emptyStroke_roundTrips() {
        val pts = StrokeCodec.decode(StrokeCodec.encode(FloatArray(0), FloatArray(0)))
        assertEquals(0, pts.size)
    }

    @Test
    fun mismatchedLengths_throw() {
        assertTrue(runCatching { StrokeCodec.encode(FloatArray(2), FloatArray(3)) }.isFailure)
        assertTrue(runCatching { StrokeCodec.encode(FloatArray(2), FloatArray(2), FloatArray(1)) }.isFailure)
        assertTrue(runCatching { StrokeCodec.encode(FloatArray(2), FloatArray(2), null, FloatArray(1)) }.isFailure)
    }

    @Test
    fun unknownVersion_throws() {
        assertTrue(runCatching { StrokeCodec.decode(byteArrayOf(9, 0, 0)) }.isFailure)
    }

    @Test
    fun emptyBlob_throws() {
        assertTrue(runCatching { StrokeCodec.decode(ByteArray(0)) }.isFailure)
    }

    /**
     * A writer newer than this one: flags 0x04 is a channel this reader does not know. Each set bit
     * is one f32 per point, so the stride is 12 — the unknown channel is skipped, never read as
     * the next point's x.
     */
    @Test
    fun unknownChannelBit_isSkipped() {
        val x = floatArrayOf(1f, 2f, 3f)
        val y = floatArrayOf(10f, 20f, 30f)
        val pts = StrokeCodec.decode(handBuiltBlob(0x04, x, y, null, null, extras = 1))
        assertEquals(3, pts.size)
        assertArrayEquals(x, pts.x, 0f)
        assertArrayEquals(y, pts.y, 0f)
        assertNull(pts.pressure)
        assertNull(pts.tilt)
    }

    /** Unknown bits alongside known ones: the known channels still read, the unknown two skipped. */
    @Test
    fun unknownChannelBits_withKnownChannels_areSkipped() {
        val x = floatArrayOf(1f, 2f)
        val y = floatArrayOf(10f, 20f)
        val p = floatArrayOf(0.5f, 0.75f)
        val t = floatArrayOf(-0.25f, 0.25f)
        val pts = StrokeCodec.decode(handBuiltBlob(0x1B, x, y, p, t, extras = 2))
        assertEquals(2, pts.size)
        assertArrayEquals(x, pts.x, 0f)
        assertArrayEquals(y, pts.y, 0f)
        assertArrayEquals(p, pts.pressure, 0f)
        assertArrayEquals(t, pts.tilt, 0f)
    }

    @Test(timeout = 5_000)
    fun corruptZlib_doesNotSpin() {
        // FDICT-style garbage after the version byte must return (fail or empty), not hang.
        val garbage = byteArrayOf(1, 0x78, 0x3C.toByte(), 1, 2, 3, 4)
        runCatching { StrokeCodec.decode(garbage) }
    }

    /** A format-B blob written by hand: x, y, the known channels, then [extras] unknown f32s a point. */
    private fun handBuiltBlob(flags: Int, x: FloatArray, y: FloatArray, p: FloatArray?, t: FloatArray?, extras: Int): ByteArray {
        val stride = 8 + (if (p != null) 4 else 0) + (if (t != null) 4 else 0) + 4 * extras
        val payload = ByteBuffer.allocate(1 + x.size * stride).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(flags.toByte())
        for (i in x.indices) {
            payload.putFloat(x[i]); payload.putFloat(y[i])
            if (p != null) payload.putFloat(p[i])
            if (t != null) payload.putFloat(t[i])
            repeat(extras) { payload.putFloat(999f + it) }
        }
        val d = Deflater()
        d.setInput(payload.array()); d.finish()
        val out = ByteArrayOutputStream()
        out.write(StrokeCodec.VERSION_FLOAT32.toInt())
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(blob: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(blob, 1, blob.size - 1)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    private operator fun Array<FloatArray?>.component1() = this[0]
    private operator fun Array<FloatArray?>.component2() = this[1]
    private operator fun Array<FloatArray?>.component3() = this[2]
    private operator fun Array<FloatArray?>.component4() = this[3]
}
