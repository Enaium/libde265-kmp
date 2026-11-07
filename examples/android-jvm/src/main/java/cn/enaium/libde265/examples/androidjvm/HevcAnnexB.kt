/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.androidjvm

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Converts between MediaCodec's HEVC bitstream framing and the Annex-B stream
 * the libde265 binding decodes.
 *
 * `MediaFormat` carries the parameter sets as an `hvcC`
 * `HEVCDecoderConfigurationRecord` (`csd-0`); output buffers are a sequence of
 * length-prefixed NAL units. The decoder wants both as start-code-prefixed
 * Annex-B, so the record is parsed once and prepended to every keyframe.
 */
internal object HevcAnnexB {
    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34
    private const val START_CODE_SIZE = 4

    /** Result of parsing `csd-0`. */
    internal data class Config(
        /** VPS/SPS/PPS as Annex-B, ready to prepend to a keyframe. */
        val prefix: ByteArray,
        /** Width of the length field that prefixes each NAL in an output buffer. */
        val lengthSize: Int,
    )

    /**
     * Parses an `hvcC` record. A buffer that already contains start codes is
     * returned unchanged, so encoders that emit Annex-B directly also work.
     */
    fun parseConfig(buffer: ByteBuffer): Config {
        val source = buffer.duplicate().let { copy ->
            val data = ByteArray(copy.remaining())
            copy.get(data)
            data
        }
        if (startCodeSize(source, 0) > 0) {
            return Config(source, 4)
        }
        require(source.size > 23) { "hvcC record is only ${source.size} bytes" }

        val lengthSize = (source[21].toInt() and 0x03) + 1
        var position = 22
        val arrays = source[position].toInt() and 0xFF
        position++

        val output = ArrayList<ByteArray>()
        repeat(arrays) {
            require(position + 3 <= source.size) { "truncated hvcC array header" }
            val nalType = source[position].toInt() and 0x3F
            val count = readUInt16(source, position + 1)
            position += 3
            repeat(count) {
                require(position + 2 <= source.size) { "truncated hvcC NAL length" }
                val size = readUInt16(source, position)
                position += 2
                require(position + size <= source.size) { "truncated hvcC NAL payload" }
                if (nalType == NAL_VPS || nalType == NAL_SPS || nalType == NAL_PPS) {
                    output.add(source.copyOfRange(position, position + size))
                }
                position += size
            }
        }
        require(output.isNotEmpty()) { "hvcC record contains no VPS/SPS/PPS" }

        var total = 0
        output.forEach { total += START_CODE_SIZE + it.size }
        val prefix = ByteArray(total)
        var offset = 0
        output.forEach { nal ->
            prefix[offset] = 0
            prefix[offset + 1] = 0
            prefix[offset + 2] = 0
            prefix[offset + 3] = 1
            offset += START_CODE_SIZE
            nal.copyInto(prefix, offset)
            offset += nal.size
        }
        return Config(prefix, lengthSize)
    }

    /**
     * Converts one output buffer to Annex-B. `prefix` is inserted in front of
     * the access unit when it is a keyframe (or when no parameter sets have
     * been sent yet).
     */
    fun toAnnexB(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        lengthSize: Int,
        prefix: ByteArray?,
        prepend: Boolean,
    ): ByteArray {
        val copy = buffer.duplicate()
        copy.position(offset)
        copy.limit(offset + size)
        if (startCodeSize(copy, offset) > 0) {
            val data = ByteArray(size)
            copy.get(data)
            if (!prepend || prefix == null) return data
            val out = ByteArray(prefix.size + data.size)
            prefix.copyInto(out, 0)
            data.copyInto(out, prefix.size)
            return out
        }

        val nals = ArrayList<ByteArray>()
        var position = offset
        val end = offset + size
        while (position + lengthSize <= end) {
            val length = readLength(copy, position, lengthSize)
            position += lengthSize
            if (length <= 0 || position + length > end) break
            val nal = ByteArray(length)
            val slice = copy.duplicate()
            slice.position(position)
            slice.get(nal)
            nals.add(nal)
            position += length
        }
        if (nals.isEmpty()) return ByteArray(0)

        var total = 0
        if (prepend && prefix != null) total += prefix.size
        nals.forEach { total += START_CODE_SIZE + it.size }
        val output = ByteArray(total)
        var out = 0
        if (prepend && prefix != null) {
            prefix.copyInto(output, 0)
            out += prefix.size
        }
        nals.forEach { nal ->
            output[out] = 0
            output[out + 1] = 0
            output[out + 2] = 0
            output[out + 3] = 1
            out += START_CODE_SIZE
            nal.copyInto(output, out)
            out += nal.size
        }
        return output
    }

    /** 4 for a four-byte start code, 3 for a three-byte one, 0 otherwise. */
    private fun startCodeSize(data: ByteArray, offset: Int): Int = when {
        offset + 4 <= data.size && data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() && data[offset + 2] == 0.toByte() &&
            data[offset + 3] == 1.toByte() -> 4
        offset + 3 <= data.size && data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte() -> 3
        else -> 0
    }

    private fun startCodeSize(buffer: ByteBuffer, offset: Int): Int {
        val slice = buffer.duplicate()
        return when {
            offset + 4 <= slice.limit() && slice.get(offset) == 0.toByte() &&
                slice.get(offset + 1) == 0.toByte() && slice.get(offset + 2) == 0.toByte() &&
                slice.get(offset + 3) == 1.toByte() -> 4
            offset + 3 <= slice.limit() && slice.get(offset) == 0.toByte() &&
                slice.get(offset + 1) == 0.toByte() && slice.get(offset + 2) == 1.toByte() -> 3
            else -> 0
        }
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readLength(buffer: ByteBuffer, offset: Int, lengthSize: Int): Int {
        var value = 0
        for (index in 0 until lengthSize) {
            value = (value shl 8) or (buffer.get(offset + index).toInt() and 0xFF)
        }
        return value
    }
}

/** The MIME type this example encodes. */
internal val HEVC_MIME: String = MediaFormat.MIMETYPE_VIDEO_HEVC

/** `COLOR_FormatYUV420Flexible`, accepted by every modern HEVC encoder. */
internal const val COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888

/** MediaCodec output flag value, kept local to avoid an API-level import. */
internal val FLAG_KEY_FRAME: Int = MediaCodec.BUFFER_FLAG_KEY_FRAME
