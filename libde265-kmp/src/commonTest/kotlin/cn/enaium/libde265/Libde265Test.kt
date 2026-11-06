/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.libde265

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decodes a real 64x64 HEVC stream: two frames produced by libx265
 * (`testsrc2`, yuv420p, in-band VPS/SPS/PPS). See [HEVC_STREAM_BASE64].
 */
class Libde265Test {
    private val stream: ByteArray get() = Base64.decode(HEVC_STREAM_BASE64)

    @Test
    fun reportsVersion() {
        assertEquals("1.1.3", Libde265.version)
        assertEquals(1, Libde265.versionMajor)
        assertEquals(1, Libde265.versionMinor)
        assertEquals(3, Libde265.versionMaintenance)
    }

    @Test
    fun decodesAnnexBStream() {
        Libde265Decoder.create().use { decoder ->
            val frames = decoder.decode(stream, pts = 7L)
            assertEquals(2, frames.size, "the stream contains two coded pictures")
            frames.forEach { frame ->
                assertEquals(64, frame.width)
                assertEquals(64, frame.height)
                assertEquals(De265Chroma.YUV420, frame.chroma)
                assertEquals(7L, frame.pts)
                assertEquals(64 * 64, frame.y.size)
                assertEquals(32 * 32, frame.u.size)
                assertEquals(32 * 32, frame.v.size)
                assertEquals(frame.byteSize, frame.y.size + frame.u.size + frame.v.size)
                assertTrue(frame.y.any { it != 0.toByte() }, "luma must not be blank")
                assertTrue(frame.u.any { it != 0.toByte() }, "chroma must not be blank")
                assertTrue(frame.v.any { it != 0.toByte() }, "chroma must not be blank")
            }
            assertTrue(decoder.flush().isEmpty(), "nothing is left after a complete access unit")
        }
    }

    @Test
    fun decodesParameterSetsSeparately() {
        val bytes = stream
        // Feed the VPS/SPS/PPS block first, then the rest of the stream. This is
        // what an encoder that keeps parameter sets out of band produces.
        val firstSlice = findStartCode(bytes, from = 3)
        Libde265Decoder.create().use { decoder ->
            assertTrue(decoder.decode(bytes.copyOfRange(0, firstSlice)).isEmpty())
            val frames = decoder.decode(bytes.copyOfRange(firstSlice, bytes.size))
            assertEquals(2, frames.size)
            assertEquals(64, frames[0].width)
        }
    }

    @Test
    fun rejectsEmptyAccessUnit() {
        Libde265Decoder.create().use { decoder ->
            assertFailsWith<IllegalArgumentException> { decoder.decode(ByteArray(0)) }
        }
    }

    @Test
    fun rejectsClosedDecoder() {
        val decoder = Libde265Decoder.create()
        decoder.close()
        assertFailsWith<IllegalStateException> { decoder.decode(stream) }
    }

    private fun findStartCode(bytes: ByteArray, from: Int): Int {
        var index = from
        while (index + 3 <= bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 1.toByte()
            ) {
                return index
            }
            index++
        }
        error("no start code after $from")
    }
}
