@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.libde265.examples.browser

import cn.enaium.libde265.De265Frame
import cn.enaium.libde265.Libde265
import cn.enaium.libde265.Libde265Decoder
import org.khronos.webgl.Uint8ClampedArray
import kotlin.io.encoding.Base64

/**
 * Browser loopback:
 *
 * 1. `navigator.mediaDevices.getUserMedia()` provides the camera stream.
 * 2. WebCodecs `VideoEncoder` encodes the camera frames as HEVC with
 *    `hevc: { format: "annexb" }`, so VPS/SPS/PPS travel in-band.
 * 3. Every encoded chunk is decoded by `Libde265Decoder` — the same embedded
 *    WebAssembly module the published js artifact uses.
 * 4. Decoded YUV420 is converted to RGBA and painted on the second canvas.
 *
 * Chrome only exposes an HEVC encoder when the platform has one (VideoToolbox
 * on macOS, MediaFoundation on Windows). When it does not, the demo falls back
 * to decoding an embedded HEVC stream with the binding, so the decoder side is
 * still exercised and visible; the status line reports exactly which path ran.
 *
 * The demo intentionally uses plain `js()` interop: WebCodecs and
 * `getUserMedia` are not covered by the Kotlin/JS DOM declarations yet, and
 * keeping the encoder in JavaScript makes the WebCodecs usage easy to compare
 * with the MDN examples.
 */
private val document: dynamic = js("document")
private val window: dynamic = js("window")
private val navigator: dynamic = js("navigator")
private val console: dynamic = js("console")

private var video: dynamic = null
private var capture: dynamic = null
private var outputCanvas: dynamic = null
private var outputContext: dynamic = null
private var status: dynamic = null
private var stats: dynamic = null

private var decoder: Libde265Decoder? = null
private var encoder: dynamic = null

private var lastFrameTime = -1.0
private var lastReportTime = 0.0
private var lastFallbackTime = 0.0
private var encodedFrames = 0L
private var decodedFrames = 0L
private var lastChunkSize = 0
private var fallbackStarted = false
private var fallbackFrames = 0

fun main() {
    buildDom()
    decoder = Libde265Decoder.create()
    startCamera()
}

private fun buildDom() {
    status = document.getElementById("status")
    stats = document.getElementById("stats")

    video = document.createElement("video")
    video.autoplay = true
    video.muted = true
    video.playsInline = true
    document.getElementById("camera").appendChild(video)

    outputCanvas = document.createElement("canvas")
    outputCanvas.width = 640
    outputCanvas.height = 480
    outputContext = outputCanvas.getContext("2d")
    document.getElementById("decoded").appendChild(outputCanvas)

    // Off-screen canvas used only to hand camera frames to VideoFrame.
    capture = document.createElement("canvas")
    capture.width = 640
    capture.height = 480

    status.textContent = "Requesting camera…"
}

private fun startCamera() {
    val constraints = js(
        """({
            audio: false,
            video: {
                width: { ideal: 640 },
                height: { ideal: 480 },
                frameRate: { ideal: 30 }
            }
        })""",
    )
    if ((js("typeof navigator.mediaDevices === 'undefined'") as Boolean)) {
        status.textContent =
            "navigator.mediaDevices is not available (serve the page over HTTPS or localhost)."
        return
    }
    navigator.mediaDevices.getUserMedia(constraints).then({ stream: dynamic ->
        video.srcObject = stream
        video.play()
        status.textContent = "Camera ready, probing the HEVC encoder…"
        encoder = createEncoder(640, 480)
        requestFrame()
    }).catch({ error: dynamic ->
        status.textContent = "Camera error: $error"
        console.error(error)
    })
}

private fun requestFrame() {
    window.requestAnimationFrame({ frameLoop() })
}

private fun frameLoop() {
    try {
        val active = encoder
        if (active != null) {
            when (active.state() as String) {
                "ready" -> encodeNewFrame(active)
                "unsupported", "unavailable", "error" -> startFallback()
            }
            drainChunks(active)
        }
        val now = window.performance.now() as Double
        if (now - lastReportTime > 500.0) {
            lastReportTime = now
            report(active)
        }
    } catch (error: Throwable) {
        status.textContent = "Pipeline error: ${error.message}"
        console.error(error)
    }
    requestFrame()
}

private fun encodeNewFrame(active: dynamic) {
    val width = (video.videoWidth as Double).toInt()
    val height = (video.videoHeight as Double).toInt()
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return

    val time = video.currentTime as Double
    if (time == lastFrameTime) return
    lastFrameTime = time

    if ((capture.width as Double).toInt() != width || (capture.height as Double).toInt() != height) {
        capture.width = width
        capture.height = height
    }
    val context = capture.getContext("2d")
    context.drawImage(video, 0, 0, width, height)

    val pts = ((window.performance.now() as Double) * 1000.0).toLong()
    val frame = js("(source, ts) => new VideoFrame(source, { timestamp: ts })")(capture, pts)
    val accepted = active.encode(frame) as Boolean
    js("(f) => f.close()")(frame)
    if (accepted) encodedFrames++
}

private fun drainChunks(active: dynamic) {
    val queue = active.poll()
    val length = (queue.length as Number).toInt()
    if (length == 0) return
    val live = decoder ?: return
    for (index in 0 until length) {
        val chunk = queue[index]
        val data = Base64.decode(chunk.data as String)
        lastChunkSize = data.size
        val pts = (chunk.timestamp as Number).toLong()
        live.decode(data, pts).forEach(::render)
    }
}

/** Decodes the embedded stream in a loop; used when WebCodecs has no HEVC encoder. */
private fun startFallback() {
    val now = window.performance.now() as Double
    if (fallbackStarted && now - lastFallbackTime < 200.0) return
    lastFallbackTime = now
    fallbackStarted = true
    val live = decoder ?: return
    val stream = Base64.decode(BROWSER_HEVC_STREAM_BASE64)
    val frames = live.decode(stream, fallbackFrames.toLong())
    fallbackFrames++
    frames.firstOrNull()?.let(::render)
}

private fun render(frame: De265Frame) {
    if (frame.chroma.value != 1) return
    val width = frame.width
    val height = frame.height
    val chromaWidth = (width + 1) / 2
    val y = frame.y
    val u = frame.u
    val v = frame.v
    val rgba = Uint8ClampedArray(width * height * 4)
    val pixels = rgba.asDynamic()
    var offset = 0
    for (row in 0 until height) {
        val yRow = row * width
        val uvRow = (row shr 1) * chromaWidth
        for (column in 0 until width) {
            val c = ((y[yRow + column].toInt() and 0xFF) - 16).coerceAtLeast(0)
            val uu = (u[uvRow + (column shr 1)].toInt() and 0xFF) - 128
            val vv = (v[uvRow + (column shr 1)].toInt() and 0xFF) - 128
            pixels[offset] = (((298 * c + 409 * vv + 128) shr 8)).coerceIn(0, 255)
            pixels[offset + 1] = (((298 * c - 100 * uu - 208 * vv + 128) shr 8)).coerceIn(0, 255)
            pixels[offset + 2] = (((298 * c + 516 * uu + 128) shr 8)).coerceIn(0, 255)
            pixels[offset + 3] = 255
            offset += 4
        }
    }
    val imageData = js("(data, w, h) => new ImageData(data, w, h)")(rgba, width, height)
    outputContext.putImageData(imageData, 0, 0)
    decodedFrames++
}

private fun report(active: dynamic?) {
    val encoderState = if (active == null) {
        "starting"
    } else {
        val error = active.error() as String
        val codec = active.codec() as String
        val label = active.state() as String
        label + if (codec.isEmpty()) "" else " ($codec)" +
            if (error.isEmpty()) "" else " error=$error"
    }
    val path = if (fallbackStarted) {
        "WebCodecs HEVC encoder unavailable — decoding the embedded stream with libde265"
    } else {
        "camera → WebCodecs HEVC (Annex-B) → libde265"
    }
    status.textContent = "libde265 ${Libde265.version} · $path"
    stats.textContent = "encoder $encoderState · encoded $encodedFrames chunks " +
        "($lastChunkSize bytes) · decoded $decodedFrames frames"
}

/**
 * Builds the WebCodecs HEVC encoder and its chunk queue. Kept in JavaScript
 * because `VideoEncoder` and `EncodedVideoChunk` have no Kotlin declarations
 * yet. `isConfigSupported()` is asynchronous, so the object starts in the
 * "probing" state and the Kotlin loop polls it.
 */
private fun createEncoder(width: Int, height: Int): dynamic = js(
    """(function(width, height) {
        let encoder = null;
        let state = 'probing';
        let lastError = '';
        let queue = [];
        let chunks = 0;
        let codecUsed = '';
        const configs = [];
        const codecs = ['hvc1.1.6.L93.B0', 'hev1.1.6.L93.B0'];
        for (let i = 0; i < codecs.length; i++) {
            const config = {};
            config.codec = codecs[i];
            config.width = width;
            config.height = height;
            config.bitrate = 2500000;
            config.framerate = 30;
            config.latencyMode = 'realtime';
            config.hevc = {};
            config.hevc.format = 'annexb';
            configs.push(config);
        }
        const start = (config) => {
            encoder = new VideoEncoder({
                output(chunk) {
                    const bytes = new Uint8Array(chunk.byteLength);
                    chunk.copyTo(bytes);
                    let text = '';
                    for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
                    const item = {};
                    item.data = btoa(text);
                    item.key = chunk.type === 'key';
                    item.timestamp = chunk.timestamp;
                    queue.push(item);
                    chunks++;
                },
                error(error) {
                    lastError = String(error && error.message || error);
                    state = 'error';
                }
            });
            encoder.configure(config);
            state = 'ready';
            codecUsed = config.codec;
        };
        const probe = (index) => {
            if (index >= configs.length) {
                state = 'unsupported';
                return;
            }
            VideoEncoder.isConfigSupported(configs[index]).then((result) => {
                if (result.supported) start(configs[index]);
                else probe(index + 1);
            }).catch((error) => {
                lastError = String(error && error.message || error);
                probe(index + 1);
            });
        };
        if (typeof VideoEncoder === 'undefined') {
            state = 'unavailable';
        } else {
            probe(0);
        }
        return {
            state() { return state; },
            codec() { return codecUsed; },
            error() { return lastError; },
            chunks() { return chunks; },
            poll() { const out = queue; queue = []; return out; },
            encode(frame) {
                if (!encoder) return false;
                try {
                    encoder.encode(frame);
                    return true;
                } catch (error) {
                    lastError = String(error && error.message || error);
                    return false;
                }
            },
            close() {
                if (encoder) {
                    try { encoder.close(); } catch (error) { /* already closed */ }
                }
            }
        };
    })""",
)(width, height)
