/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.androidjvm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.enaium.libde265.Libde265
import kotlin.math.max

/**
 * CameraX -> MediaCodec HEVC encoder -> libde265 decoder -> RGBA preview.
 *
 * The whole loop stays in memory: captured I420 frames are encoded by the
 * platform HEVC encoder (no software encoding), the Annex-B access units are
 * decoded by the Kotlin Multiplatform libde265 binding, and the decoded RGBA
 * picture is drawn into a plain [VideoPreviewView].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch the binding before the first frame so the native library is
        // loaded on the UI thread and failures surface early.
        runCatching { Libde265.version }
        setContent {
            MaterialTheme {
                CameraScreen()
            }
        }
    }
}

@Composable
private fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var running by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<PipelineStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<VideoPreviewView?>(null) }
    // The pipeline renders into the preview bitmap from its worker thread; the
    // view draws it on the main thread, so both sides share this lock.
    val frameLock = remember { Any() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoPreviewView(ctx, frameLock).also { preview = it }
                },
            )
            if (!permissionGranted) {
                Text(
                    text = "Camera permission is required",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = permissionGranted && !running,
                    onClick = {
                        error = null
                        stats = null
                        running = true
                    },
                ) { Text("Start") }
                Button(
                    enabled = running,
                    onClick = { running = false },
                ) { Text("Stop") }
            }

            StatsCard(stats = stats, error = error)
        }
    }

    // The pipeline only exists while the Start toggle is live.
    DisposableEffect(running, permissionGranted) {
        if (!running || !permissionGranted) {
            return@DisposableEffect onDispose { }
        }
        val pipeline = HevcPipeline(
            frameLock = frameLock,
            onFrame = { bitmap -> preview?.setFrame(bitmap) },
            onStats = { updated -> stats = updated },
            onError = { throwable ->
                error = throwable.message ?: throwable.toString()
                running = false
            },
        )
        val capture = CameraCapture(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onFrame = { frame -> pipeline.submit(frame) },
            onError = { throwable ->
                error = throwable.message ?: throwable.toString()
                running = false
            },
        )
        pipeline.start()
        capture.start()
        onDispose {
            capture.stop()
            pipeline.stop()
        }
    }

    // CameraX keeps the process camera provider alive; releasing it here avoids
    // leaking a camera session when the activity is recreated.
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
    }
}

@Composable
private fun StatsCard(stats: PipelineStats?, error: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "libde265 ${Libde265.version}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (error != null) {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
            val current = stats
            if (current == null) {
                Text("Idle", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${current.width}x${current.height} via ${current.encoder}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "encoded ${current.encodedFrames} / decoded ${current.decodedFrames} " +
                        "(${current.droppedFrames} dropped)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "%.1f fps, %.0f kbit/s".format(current.fps, bitrateKbps(current)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun bitrateKbps(stats: PipelineStats): Double {
    if (stats.encodedFrames == 0L) return 0.0
    val bytesPerSecond = stats.bytes.toDouble() / max(stats.encodedFrames, 1)
    return bytesPerSecond * 30.0 * 8.0 / 1000.0
}

/**
 * Draws the newest decoded bitmap scaled to fit, without allocating a new
 * bitmap per frame (the pipeline reuses its own buffer).
 */
private class VideoPreviewView(context: Context, private val frameLock: Any) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    @Volatile
    private var frame: Bitmap? = null

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bitmap = frame ?: return
        if (bitmap.isRecycled) return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        synchronized(frameLock) {
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
                paint,
            )
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
