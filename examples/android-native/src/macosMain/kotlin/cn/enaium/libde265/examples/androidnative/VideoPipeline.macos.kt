/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.libde265.examples.androidnative

import cn.enaium.libde265.De265Chroma
import cn.enaium.libde265.De265Frame
import cn.enaium.libde265.Libde265Decoder
import cn.enaium.libyuv.LibYuv
import kotlin.io.encoding.Base64

/**
 * Desktop preview pipeline.
 *
 * Android's MediaCodec encoder is not available on macOS, so this target
 * decodes an embedded HEVC stream with the same libde265 binding the Android
 * build links and presents the pictures. It is what lets the example be run
 * and the decoder inspected without a device.
 */
private class DesktopVideoPipeline : VideoPipeline {
    private val decoder = Libde265Decoder.create()
    private var frames: List<De265Frame> = emptyList()
    private var index = 0
    private var latest: VideoFrame? = null
    private var rgba = ByteArray(0)
    private var rotationIndex = 0
    private var decodedFrames = 0L
    private var error: String? = null
    private var decodedBytes = 0L

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = "embedded HEVC stream (desktop preview)",
            path = "Annex-B HEVC -> libde265 decode -> I420 -> RGBA",
            rotation = "0 deg",
            encodedFrames = frames.size.toLong(),
            encodedBytes = decodedBytes,
            decodedFrames = decodedFrames,
            error = error,
        )

    override fun pump() {
        try {
            if (frames.isEmpty()) {
                frames = decoder.decode(Base64.decode(EXAMPLE_HEVC_STREAM_BASE64), 0L)
                if (frames.isEmpty()) {
                    error = "the embedded stream decoded to no pictures"
                    return
                }
            }
            val frame = frames[index % frames.size]
            index++
            val width = frame.width
            val height = frame.height
            if (frame.chroma != De265Chroma.YUV420) {
                error = "unexpected chroma ${frame.chroma}"
                return
            }
            val size = width * height * 4
            if (rgba.size != size) rgba = ByteArray(size)
            LibYuv.i420ToRgbaBt709(frame.y, frame.u, frame.v, rgba, width, height)
            latest = VideoFrame(width, height, rgba.copyOf())
            decodedFrames++
            decodedBytes += frame.byteSize
            error = null
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        }
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        index = 0
        latest = null
        decodedFrames = 0
        decodedBytes = 0
        error = null
    }

    override fun cycleRotation() {
        // The embedded stream is already upright.
        rotationIndex = (rotationIndex + 1) % 4
    }

    override fun close() {
        decoder.close()
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = DesktopVideoPipeline()
