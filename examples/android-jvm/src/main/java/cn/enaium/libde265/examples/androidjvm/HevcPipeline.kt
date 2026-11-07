/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.androidjvm

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import cn.enaium.libde265.De265Chroma
import cn.enaium.libde265.De265Frame
import cn.enaium.libde265.Libde265
import cn.enaium.libde265.Libde265Decoder
import cn.enaium.libyuv.FourCC
import cn.enaium.libyuv.I420Frame
import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import cn.enaium.libyuv.writeI420
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal data class PipelineStats(
    val encodedFrames: Long,
    val decodedFrames: Long,
    val droppedFrames: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val encoder: String,
)

/**
 * Camera frames in, decoded video out.
 *
 * The loop mirrors a real streaming app: CameraX produces YUV frames,
 * MediaCodec (hardware) encodes them as HEVC, and the Kotlin Multiplatform
 * libde265 binding decodes the Annex-B access units. Decoded I420 is converted
 * to RGBA with libyuv and handed to the UI as a [Bitmap].
 *
 * Only one thread touches the encoder and the decoder; [submit] is the only
 * method safe to call from the camera executor.
 */
internal class HevcPipeline(
    private val onFrame: (Bitmap) -> Unit,
    private val onStats: (PipelineStats) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val queue = ArrayBlockingQueue<I420Frame>(3)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runPipeline() }, "de265-hevc").apply { start() }
    }

    fun submit(frame: I420Frame) {
        if (!running) return
        // Keep the newest frame: encoding slower than the camera must not grow
        // an unbounded backlog.
        while (!queue.offer(frame)) {
            queue.poll()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        thread?.join(1_000)
        thread = null
    }

    private fun runPipeline() {
        val session = Session(onFrame, onStats)
        try {
            while (running || queue.isNotEmpty()) {
                val frame = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                session.encode(frame)
            }
            session.finish()
        } catch (t: Throwable) {
            onError(t)
        } finally {
            session.close()
        }
    }

    private class Session(
        private val onFrame: (Bitmap) -> Unit,
        private val onStats: (PipelineStats) -> Unit,
    ) {
        private var encoder: MediaCodec? = null
        private var decoder: Libde265Decoder? = null
        private var config: HevcAnnexB.Config? = null
        private var parameterSetsSent = false
        private var encoderName = ""
        private var width = 0
        private var height = 0
        private var encodedFrames = 0L
        private var decodedFrames = 0L
        private var droppedFrames = 0L
        private var bytes = 0L
        private var lastPtsUs = 0L
        private var lastReportMs = SystemClock.elapsedRealtime()
        private var framesSinceReport = 0
        private var rgba = ByteArray(0)
        private var bitmap: Bitmap? = null

        fun encode(frame: I420Frame) {
            val rotated = rotate(frame)
            val live = encoder ?: createEncoder(rotated)
            if (rotated.width != width || rotated.height != height) {
                droppedFrames++
                return
            }
            feed(live, rotated)
            drain(live)
            report(force = false)
        }

        fun finish() {
            val live = encoder ?: return
            signalEndOfStream(live)
            drain(live)
            decoder?.flush()?.forEach(::render)
            report(force = true)
        }

        fun close() {
            val codec = encoder
            encoder = null
            if (codec != null) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder?.close()
            decoder = null
        }

        private fun rotate(frame: I420Frame): I420Frame {
            val rotation = when (frame.rotationDegrees) {
                90 -> Rotation.CLOCKWISE_90
                180 -> Rotation.ROTATE_180
                270 -> Rotation.CLOCKWISE_270
                else -> Rotation.NONE
            }
            return if (rotation == Rotation.NONE) frame else frame.rotate(rotation)
        }

        private fun createEncoder(frame: I420Frame): MediaCodec {
            width = frame.width
            height = frame.height
            val format = MediaFormat.createVideoFormat(HEVC_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_YUV420_FLEXIBLE)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(HEVC_MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            encoderName = codec.name
            decoder = Libde265Decoder.create()
            Log.i(
                TAG,
                "encoder=$encoderName ${width}x$height, decoder=libde265 ${Libde265.version}",
            )
            return codec
        }

        private fun feed(codec: MediaCodec, frame: I420Frame) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                droppedFrames++
                return
            }
            val pts = if (frame.presentationTimeUs > 0) frame.presentationTimeUs else lastPtsUs + 33_333
            lastPtsUs = pts
            // Qualcomm's HEVC encoder rejects the Image buffer that
            // getInputImage() hands out ("Unsupported input buffer") while it
            // accepts a packed I420 byte buffer, so always use the latter.
            val packed = frame.toPackedBuffer(FourCC.I420)
            val buffer = codec.getInputBuffer(index)
            if (buffer == null) {
                codec.queueInputBuffer(index, 0, 0, pts, 0)
                droppedFrames++
                return
            }
            buffer.clear()
            val packedSize = packed.remaining()
            buffer.put(packed)
            codec.queueInputBuffer(index, 0, packedSize, pts, 0)
        }

        private fun drain(codec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        format.getByteBuffer("csd-0")?.let { csd ->
                            config = runCatching { HevcAnnexB.parseConfig(csd) }
                                .onFailure { Log.e(TAG, "hvcC parse failed", it) }
                                .getOrNull()
                            Log.i(TAG, "csd: ${config?.prefix?.size ?: 0} bytes")
                        }
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                config = HevcAnnexB.parseConfig(buffer)
                            } else {
                                val current = config
                                val annexB = HevcAnnexB.toAnnexB(
                                    buffer = buffer,
                                    offset = info.offset,
                                    size = info.size,
                                    lengthSize = current?.lengthSize ?: 4,
                                    prefix = current?.prefix,
                                    prepend = keyFrame || !parameterSetsSent,
                                )
                                if (annexB.isNotEmpty()) {
                                    parameterSetsSent = true
                                    bytes += annexB.size
                                    encodedFrames++
                                    framesSinceReport++
                                    decoder?.decode(annexB, info.presentationTimeUs)?.forEach(::render)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                    else -> return
                }
            }
        }

        private fun render(frame: De265Frame) {
            decodedFrames++
            if (frame.chroma != De265Chroma.YUV420) {
                Log.w(TAG, "skipping ${frame.chroma} frame; the preview converts 4:2:0 only")
                return
            }
            val size = frame.width * frame.height * 4
            if (rgba.size != size) rgba = ByteArray(size)
            LibYuv.i420ToRgba(
                frame.y, frame.u, frame.v, rgba,
                frame.width, frame.height,
            )
            val target = bitmap?.takeIf { it.width == frame.width && it.height == frame.height }
                ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    .also { bitmap = it }
            target.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            onFrame(target)
        }

        private fun signalEndOfStream(codec: MediaCodec) {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    max(lastPtsUs, 1),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
        }

        private fun report(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastReportMs
            if (!force && elapsed < 500) return
            val fps = if (elapsed > 0) framesSinceReport * 1000.0 / elapsed else 0.0
            onStats(
                PipelineStats(
                    encodedFrames = encodedFrames,
                    decodedFrames = decodedFrames,
                    droppedFrames = droppedFrames,
                    bytes = bytes,
                    width = width,
                    height = height,
                    fps = fps,
                    encoder = encoderName,
                ),
            )
            lastReportMs = now
            framesSinceReport = 0
        }

        private companion object {
            const val TAG = "de265-hevc"
            const val BIT_RATE = 2_500_000
            const val FRAME_RATE = 30
        }
    }
}
