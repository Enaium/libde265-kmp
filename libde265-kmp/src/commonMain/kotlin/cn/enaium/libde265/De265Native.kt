/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265

/**
 * Opaque decoder owned by the native library. The actual classes wrap a
 * `dk_decoder*` (JNI/Kotlin-Native) or a WebAssembly pointer.
 */
internal expect class De265Handle() {
    // The actuals add the platform pointer; the common API only passes handles
    // between De265Native calls.
}

/**
 * Bridge to the C++ shim, implemented for JNI, Kotlin/Native cinterop and the
 * WebAssembly module. Every implementation follows the same contract:
 *
 * - [decode]/[flush] return the packed `DE265_OUTPUT_MAGIC` blob, or `null`
 *   when no picture is ready;
 * - [lastError] is the libde265 error code of the last call, 0 when it
 *   succeeded;
 * - [lastWarning] is the last non-fatal libde265 warning code, 0 when there
 *   was none.
 */
internal expect object De265Native {
    fun versionNumber(): Int

    fun versionString(): String

    fun versionMajor(): Int

    fun versionMinor(): Int

    fun versionMaintenance(): Int

    fun decoderCreate(): De265Handle?

    fun decoderClose(handle: De265Handle)

    fun decoderSetThreads(handle: De265Handle, threads: Int): Int

    fun decode(handle: De265Handle, data: ByteArray, pts: Long): ByteArray?

    fun flush(handle: De265Handle): ByteArray?

    fun lastError(handle: De265Handle): Int

    fun lastWarning(handle: De265Handle): Int

    fun errorText(code: Int): String
}

/** `DK_OUTPUT_MAGIC`, see `jni/include/de265_kmp.h`. */
internal const val DE265_OUTPUT_MAGIC: Int = 0x4B503644

/** `DK_OUTPUT_VERSION`. */
internal const val DE265_OUTPUT_VERSION: Int = 1

/** `DK_OK`: at least one picture is available. */
internal const val DE265_OK: Int = 1

/** `DK_BUFFERED`: the input was accepted but no picture is ready yet. */
internal const val DE265_BUFFERED: Int = 0

/** Size of one frame record in the blob. */
internal const val DE265_FRAME_RECORD: Int = 64

/**
 * Parser for the packed result blob. Written by hand instead of using a
 * platform `ByteBuffer` so the exact same code runs on JVM, Kotlin/Native and
 * the web.
 */
internal object De265Output {
    private const val FIXED_HEADER = 16

    fun parse(bytes: ByteArray): List<De265Frame> {
        require(bytes.size >= FIXED_HEADER) { "truncated libde265 output (${bytes.size} bytes)" }
        val magic = readInt(bytes, 0)
        check(magic == DE265_OUTPUT_MAGIC) {
            "bad libde265 output magic 0x${magic.toUInt().toString(16)}"
        }
        val version = readInt(bytes, 4)
        check(version == DE265_OUTPUT_VERSION) {
            "unsupported libde265 output version $version"
        }
        val count = readInt(bytes, 8)
        require(count >= 0) { "negative frame count $count" }
        val payloadBase = readInt(bytes, 12)
        require(payloadBase >= FIXED_HEADER + count * DE265_FRAME_RECORD) {
            "payload offset $payloadBase overlaps the frame records"
        }
        require(payloadBase <= bytes.size) { "truncated libde265 output" }

        val frames = ArrayList<De265Frame>(count)
        for (index in 0 until count) {
            val record = FIXED_HEADER + index * DE265_FRAME_RECORD
            val width = readInt(bytes, record)
            val height = readInt(bytes, record + 4)
            val chroma = De265Chroma.fromValue(readInt(bytes, record + 8))
            val pts = readLong(bytes, record + 16)
            val ySize = readInt(bytes, record + 24)
            val uSize = readInt(bytes, record + 28)
            val vSize = readInt(bytes, record + 32)
            val yOffset = readInt(bytes, record + 36)
            val uOffset = readInt(bytes, record + 40)
            val vOffset = readInt(bytes, record + 44)
            require(width > 0 && height > 0) { "frame $index has an invalid size" }
            require(ySize >= 0 && uSize >= 0 && vSize >= 0) { "frame $index has invalid planes" }
            frames.add(
                De265Frame(
                    width = width,
                    height = height,
                    chroma = chroma,
                    pts = pts,
                    y = plane(bytes, payloadBase, yOffset, ySize),
                    u = plane(bytes, payloadBase, uOffset, uSize),
                    v = plane(bytes, payloadBase, vOffset, vSize),
                ),
            )
        }
        return frames
    }

    private fun plane(bytes: ByteArray, base: Int, offset: Int, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && base + offset + size <= bytes.size) {
            "plane at $offset ($size bytes) is outside the payload"
        }
        return bytes.copyOfRange(base + offset, base + offset + size)
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLong(source: ByteArray, offset: Int): Long =
        (readInt(source, offset).toLong() and 0xFFFFFFFFL) or
            (readInt(source, offset + 4).toLong() shl 32)
}
