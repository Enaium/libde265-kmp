/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265

/**
 * Thrown when the native libde265 library rejects a call: an invalid access
 * unit, a failed decoder allocation, or a fatal decoder error.
 */
public class De265Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Chroma format of a decoded picture, mirroring libde265's `de265_chroma`.
 */
public enum class De265Chroma(public val value: Int) {
    /** Monochrome, luma only. */
    MONO(0),

    /** 4:2:0, chroma planes are half the luma size in both directions. */
    YUV420(1),

    /** 4:2:2, chroma planes are half the luma width and the full height. */
    YUV422(2),

    /** 4:4:4, chroma planes match the luma size. */
    YUV444(3);

    public companion object {
        public fun fromValue(value: Int): De265Chroma =
            entries.firstOrNull { it.value == value }
                ?: throw De265Exception("unsupported libde265 chroma format $value")
    }
}

/**
 * One decoded picture.
 *
 * The planes are tightly packed 8-bit YUV: [y] has [width] x [height] bytes,
 * [u] and [v] have [chromaWidth] x [chromaHeight] bytes. The decoder copies
 * them out of libde265's picture buffer before it is recycled, so the arrays
 * stay valid after the next call.
 */
public class De265Frame internal constructor(
    public val width: Int,
    public val height: Int,
    public val chroma: De265Chroma,
    public val pts: Long,
    public val y: ByteArray,
    public val u: ByteArray,
    public val v: ByteArray,
) {
    /** Width of the chroma planes, 0 for [De265Chroma.MONO]. */
    public val chromaWidth: Int
        get() = when (chroma) {
            De265Chroma.MONO -> 0
            De265Chroma.YUV420, De265Chroma.YUV422 -> (width + 1) / 2
            De265Chroma.YUV444 -> width
        }

    /** Height of the chroma planes, 0 for [De265Chroma.MONO]. */
    public val chromaHeight: Int
        get() = when (chroma) {
            De265Chroma.MONO -> 0
            De265Chroma.YUV420 -> (height + 1) / 2
            De265Chroma.YUV422, De265Chroma.YUV444 -> height
        }

    /** Total number of plane bytes in [y], [u] and [v]. */
    public val byteSize: Int
        get() = y.size + u.size + v.size

    override fun toString(): String =
        "De265Frame(${width}x$height, $chroma, pts=$pts, $byteSize bytes)"
}
