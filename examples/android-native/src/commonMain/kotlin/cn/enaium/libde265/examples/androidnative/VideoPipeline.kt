/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.androidnative

/** One RGBA frame ready for the ImGui draw list. */
internal data class VideoFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

/** What the pipeline is doing, shown above the image. */
internal data class PipelineStatus(
    val source: String,
    val path: String,
    val rotation: String,
    val encodedFrames: Long,
    val encodedBytes: Long,
    val decodedFrames: Long,
    val error: String?,
)

/**
 * The capture -> convert -> encode -> decode -> present loop.
 *
 * Android native implements it with the Camera2 NDK, the NDK `AMediaCodec`
 * HEVC encoder, the Kotlin Multiplatform libde265 decoder and libyuv. The
 * desktop build substitutes an embedded HEVC stream and decodes it with the
 * same binding, because Android's MediaCodec is not available there; the UI
 * code is shared.
 */
internal interface VideoPipeline {

    val status: PipelineStatus

    /** Advances capture and both codecs once; never blocks the caller. */
    fun pump()

    /** The newest decoded frame, or null until the first one is available. */
    fun currentFrame(): VideoFrame?

    /** Tears the current camera/codecs down and starts them again. */
    fun restart()

    /** Rotates the frames before encoding, correcting the sensor orientation. */
    fun cycleRotation()

    /** Releases the camera and the codecs. */
    fun close()
}

internal expect fun createVideoPipeline(): VideoPipeline
