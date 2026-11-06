/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.libde265.examples.androidnative

import cn.enaium.libde265.De265Chroma
import cn.enaium.libde265.De265Frame
import cn.enaium.libde265.Libde265
import cn.enaium.libde265.Libde265Decoder
import cn.enaium.libyuv.FourCC
import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import cn.enaium.sdl.SDL
import cnames.structs.AMediaCodec
import cnames.structs.AMediaFormat
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.media.AMediaCodecBufferInfo
import platform.media.AMediaCodec_configure
import platform.media.AMediaCodec_createEncoderByType
import platform.media.AMediaCodec_dequeueInputBuffer
import platform.media.AMediaCodec_dequeueOutputBuffer
import platform.media.AMediaCodec_delete
import platform.media.AMediaCodec_getInputBuffer
import platform.media.AMediaCodec_getOutputBuffer
import platform.media.AMediaCodec_getOutputFormat
import platform.media.AMediaCodec_queueInputBuffer
import platform.media.AMediaCodec_releaseOutputBuffer
import platform.media.AMediaCodec_start
import platform.media.AMediaCodec_stop
import platform.media.AMediaFormat_delete
import platform.media.AMediaFormat_getBuffer
import platform.media.AMediaFormat_new
import platform.media.AMediaFormat_setInt32
import platform.media.AMediaFormat_setString
import platform.media.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
import platform.media.AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
import platform.media.AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED
import platform.media.AMEDIACODEC_INFO_TRY_AGAIN_LATER
import platform.posix.memcpy
import platform.posix.size_tVar

private const val MIME_HEVC = "video/hevc"

/** `COLOR_FormatYUV420SemiPlanar`: an NV12 buffer, the layout encoders accept. */
private const val COLOR_YUV420_SEMI_PLANAR = 21

/** Microseconds between two camera frames at 30 fps. */
private const val FRAME_INTERVAL_US = 33_333L

/**
 * The Android native loopback:
 *
 *   Camera2 (YUV_420_888) -> libyuv Android420ToI420
 *     -> NDK AMediaCodec HEVC encoder
 *     -> libde265 decoder (the Kotlin Multiplatform binding linked into libmain.so)
 *     -> libyuv I420ToRGBA -> ImGui texture
 */
private class AndroidVideoPipeline : VideoPipeline {
    private var camera: Camera2Source? = null
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var encodedWidth = 0
    private var encodedHeight = 0
    private var encoder: CPointer<AMediaCodec>? = null
    private var config: HevcAnnexB.Config? = null
    private var parameterSetsSent = false
    private var decoder: Libde265Decoder? = null

    private var sourceY = ByteArray(0)
    private var sourceU = ByteArray(0)
    private var sourceV = ByteArray(0)
    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var nv12 = ByteArray(0)

    private var latest: VideoFrame? = null
    private var started = false
    private var retryAt = 0uL
    private var error: String? = null
    private var encodedFrames = 0L
    private var encodedBytes = 0L
    private var decodedFrames = 0L
    private var presentationTimeUs = 0L
    private var rotationIndex = 0

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = "camera ${cameraWidth}x$cameraHeight Camera2 YUV_420_888",
            path = "camera -> I420 -> AMediaCodec HEVC encode -> libde265 decode -> RGBA",
            rotation = rotationName(),
            encodedFrames = encodedFrames,
            encodedBytes = encodedBytes,
            decodedFrames = decodedFrames,
            error = error,
        )

    override fun pump() {
        if (!started) {
            val now = SDL.getTicks()
            if (now < retryAt) return
            if (!start()) {
                retryAt = SDL.getTicks() + 2_000u
                return
            }
        }
        try {
            pumpCamera()
            drainEncoder()
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        close()
        latest = null
        encodedFrames = 0
        encodedBytes = 0
        decodedFrames = 0
        presentationTimeUs = 0
        retryAt = 0u
        error = null
    }

    override fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % Rotation.entries.size
        restart()
    }

    override fun close() {
        camera?.close()
        camera = null
        closeCodecs()
        started = false
    }

    private fun start(): Boolean = try {
        val source = Camera2Source(640, 480).also { it.open() }
        camera = source
        cameraWidth = source.width
        cameraHeight = source.height
        encodedWidth = if (rotated()) cameraHeight else cameraWidth
        encodedHeight = if (rotated()) cameraWidth else cameraHeight
        allocateSourceBuffers()
        startEncoder()
        decoder = Libde265Decoder.create()
        started = true
        error = null
        true
    } catch (t: Throwable) {
        close()
        error = t.message ?: t.toString()
        false
    }

    private fun allocateSourceBuffers() {
        val w = cameraWidth
        val h = cameraHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        sourceY = ByteArray(w * h)
        sourceU = ByteArray(cw * ch)
        sourceV = ByteArray(cw * ch)
        nv12 = ByteArray(encodedWidth * encodedHeight * 3 / 2)
    }

    private fun startEncoder() {
        val codec = AMediaCodec_createEncoderByType(MIME_HEVC)
            ?: throw IllegalStateException("no HEVC encoder")
        val format = AMediaFormat_new() ?: run {
            AMediaCodec_delete(codec)
            throw IllegalStateException("AMediaFormat_new failed")
        }
        try {
            AMediaFormat_setString(format, "mime", MIME_HEVC)
            AMediaFormat_setInt32(format, "width", encodedWidth)
            AMediaFormat_setInt32(format, "height", encodedHeight)
            AMediaFormat_setInt32(format, "color-format", COLOR_YUV420_SEMI_PLANAR)
            AMediaFormat_setInt32(format, "bitrate", 2_500_000)
            AMediaFormat_setInt32(format, "frame-rate", 30)
            AMediaFormat_setInt32(format, "i-frame-interval", 1)
            val configure = AMediaCodec_configure(codec, format, null, null, 1u)
            if (configure != 0 || AMediaCodec_start(codec) != 0) {
                throw IllegalStateException("encoder configure/start failed ($configure)")
            }
            encoder = codec
        } catch (t: Throwable) {
            AMediaCodec_delete(codec)
            throw t
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun pumpCamera() {
        val cam = camera ?: return
        if (!cam.acquire(sourceY, sourceU, sourceV)) return
        val frame = rotatedI420(sourceY, sourceU, sourceV)
        val pts = presentationTimeUs
        presentationTimeUs += FRAME_INTERVAL_US
        encodeFrame(frame.first, frame.second, frame.third, pts)
    }

    private fun rotatedI420(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val rotation = Rotation.entries[rotationIndex]
        if (rotation == Rotation.NONE) return Triple(y, u, v)
        val w = encodedWidth
        val h = encodedHeight
        if (rotatedY.size != w * h) {
            rotatedY = ByteArray(w * h)
            rotatedU = ByteArray(((w + 1) / 2) * ((h + 1) / 2))
            rotatedV = ByteArray(rotatedU.size)
        }
        LibYuv.i420Rotate(
            y, u, v,
            rotatedY, rotatedU, rotatedV,
            cameraWidth, cameraHeight, rotation,
        )
        return Triple(rotatedY, rotatedU, rotatedV)
    }

    private fun encodeFrame(y: ByteArray, u: ByteArray, v: ByteArray, ptsUs: Long) {
        val codec = encoder ?: return
        val index = AMediaCodec_dequeueInputBuffer(codec, 0)
        if (index < 0) return
        memScoped {
            val capacity = alloc<size_tVar>()
            val buffer = AMediaCodec_getInputBuffer(codec, index.convert(), capacity.ptr)
                ?: throw IllegalStateException("encoder input buffer is null")
            // Semi-planar input: one NV12 buffer. libyuv writes Y, then the
            // interleaved UV plane, exactly what COLOR_FormatYUV420SemiPlanar
            // expects from a ByteBuffer.
            LibYuv.convertFromI420(
                y, u, v, nv12, encodedWidth, encodedHeight, FourCC.NV12,
            )
            if (nv12.size > capacity.value.toLong()) {
                throw IllegalStateException("encoder input ${capacity.value} < frame ${nv12.size}")
            }
            buffer.write(nv12, nv12.size)
            val result = AMediaCodec_queueInputBuffer(
                codec,
                index.convert(),
                0,
                nv12.size.convert(),
                ptsUs.toULong(),
                0u,
            )
            if (result != 0) throw IllegalStateException("encoder queueInputBuffer=$result")
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index.toLong() == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index.toLong() == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) {
                    readEncoderFormat(codec)
                    continue
                }
                if (index < 0) break
                if (info.size > 0) {
                    if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        val buffer = AMediaCodec_getOutputBuffer(codec, index.convert(), bufferSize.ptr)
                        if (buffer != null) {
                            config = HevcAnnexB.parseConfig(
                                buffer.read(info.offset, info.size),
                            )
                        }
                    } else {
                        val buffer = AMediaCodec_getOutputBuffer(codec, index.convert(), bufferSize.ptr)
                        if (buffer != null) {
                            val source = buffer.read(info.offset, info.size)
                            val current = config
                            val annexB = HevcAnnexB.toAnnexB(
                                source = source,
                                offset = 0,
                                size = source.size,
                                lengthSize = current?.lengthSize ?: 4,
                                prefix = current?.prefix,
                                prepend = !parameterSetsSent || HevcAnnexB.isKeyFrame(source),
                            )
                            if (annexB.isNotEmpty()) {
                                parameterSetsSent = true
                                encodedFrames++
                                encodedBytes += annexB.size.toLong()
                                decoder?.decode(annexB, info.presentationTimeUs)?.forEach(::render)
                            }
                        }
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.convert(), false)
                if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun readEncoderFormat(codec: CPointer<AMediaCodec>) {
        val format = AMediaCodec_getOutputFormat(codec) ?: return
        try {
            memScoped {
                val data = alloc<CPointerVarOf<CPointer<out kotlinx.cinterop.CPointed>>>()
                val size = alloc<size_tVar>()
                if (AMediaFormat_getBuffer(format, "csd-0", data.ptr, size.ptr)) {
                    val bytes = data.value?.reinterpret<ByteVar>()?.readFast(size.value.toInt())
                        ?: ByteArray(0)
                    if (bytes.isNotEmpty()) config = HevcAnnexB.parseConfig(bytes)
                }
            }
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun render(frame: De265Frame) {
        if (frame.chroma != De265Chroma.YUV420) return
        val rgba = ByteArray(frame.width * frame.height * 4)
        LibYuv.i420ToRgba(
            frame.y, frame.u, frame.v, rgba,
            frame.width, frame.height,
        )
        latest = VideoFrame(frame.width, frame.height, rgba)
        decodedFrames++
    }

    private fun closeCodecs() {
        encoder?.let {
            AMediaCodec_stop(it)
            AMediaCodec_delete(it)
        }
        encoder = null
        decoder?.close()
        decoder = null
        config = null
        parameterSetsSent = false
    }

    private fun fail(message: String) {
        error = message
        close()
        retryAt = SDL.getTicks() + 2_000u
    }

    private fun rotated(): Boolean =
        Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_90 ||
            Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_270

    private fun rotationName(): String = when (Rotation.entries[rotationIndex]) {
        Rotation.NONE -> "0 deg"
        Rotation.CLOCKWISE_90 -> "90 deg"
        Rotation.ROTATE_180 -> "180 deg"
        Rotation.CLOCKWISE_270 -> "270 deg"
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = AndroidVideoPipeline()

private fun CPointer<UByteVar>.write(bytes: ByteArray, length: Int) {
    if (length == 0) return
    val dst = interpretCPointer<ByteVar>(rawValue) ?: return
    bytes.usePinned { memcpy(dst, it.addressOf(0), length.convert()) }
}

private fun CPointer<UByteVar>.read(offset: Int, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val base = interpretCPointer<ByteVar>(rawValue)
        ?: throw IllegalStateException("null MediaCodec buffer")
    return base.plus(offset)?.readFast(length) ?: ByteArray(0)
}

private fun CPointer<*>.readFast(length: Int): ByteArray {
    val out = ByteArray(length)
    if (length == 0) return out
    out.usePinned { memcpy(it.addressOf(0), this, length.convert()) }
    return out
}
