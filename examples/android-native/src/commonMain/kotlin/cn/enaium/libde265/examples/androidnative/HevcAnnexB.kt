/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.androidnative

/**
 * Parses the `hvcC` `HEVCDecoderConfigurationRecord` MediaCodec exposes as
 * `csd-0`, and converts length-prefixed output buffers to Annex-B.
 *
 * Kept in commonMain because the NDK pipeline and the desktop preview share it.
 */
internal object HevcAnnexB {
    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34
    private const val START_CODE_SIZE = 4

    internal data class Config(
        val prefix: ByteArray,
        val lengthSize: Int,
    )

    fun parseConfig(source: ByteArray): Config {
        if (source.size >= 4 && isStartCode(source, 0)) {
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

    fun toAnnexB(
        source: ByteArray,
        offset: Int,
        size: Int,
        lengthSize: Int,
        prefix: ByteArray?,
        prepend: Boolean,
    ): ByteArray {
        if (size <= 0 || offset < 0 || offset + size > source.size) return ByteArray(0)
        if (size >= 4 && isStartCode(source, offset)) {
            return source.copyOfRange(offset, offset + size)
        }

        val nals = ArrayList<ByteArray>()
        var position = offset
        val end = offset + size
        while (position + lengthSize <= end) {
            val length = readLength(source, position, lengthSize)
            position += lengthSize
            if (length <= 0 || position + length > end) break
            nals.add(source.copyOfRange(position, position + length))
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

    /** True when the Annex-B access unit contains an HEVC IRAP slice. */
    fun isKeyFrame(annexB: ByteArray): Boolean {
        var position = 0
        while (position + 4 <= annexB.size) {
            if (annexB[position] == 0.toByte() && annexB[position + 1] == 0.toByte() &&
                annexB[position + 2] == 1.toByte()
            ) {
                val header = position + 3
                if (header + 1 < annexB.size) {
                    val type = (annexB[header].toInt() shr 1) and 0x3F
                    if (type in 16..23) return true
                }
                position = header + 1
            } else {
                position++
            }
        }
        return false
    }

    private fun isStartCode(data: ByteArray, offset: Int): Boolean =
        offset + 3 <= data.size && data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readLength(data: ByteArray, offset: Int, lengthSize: Int): Int {
        var value = 0
        for (index in 0 until lengthSize) {
            value = (value shl 8) or (data[offset + index].toInt() and 0xFF)
        }
        return value
    }
}
