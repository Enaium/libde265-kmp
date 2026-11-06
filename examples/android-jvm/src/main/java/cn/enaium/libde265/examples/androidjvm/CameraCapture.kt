/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package cn.enaium.libde265.examples.androidjvm

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import cn.enaium.libyuv.I420Frame
import cn.enaium.libyuv.toI420
import java.util.concurrent.Executors

/**
 * CameraX `ImageAnalysis` source for [HevcPipeline].
 *
 * Frames are delivered on a single background thread and dropped by the camera
 * when the encoder cannot keep up (`STRATEGY_KEEP_ONLY_LATEST`).
 */
internal class CameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (I420Frame) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "de265-camera")
    }
    private var provider: ProcessCameraProvider? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                bind(future.get())
            } catch (t: Throwable) {
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        if (!running) return
        provider = cameraProvider

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(CAPTURE_WIDTH, CAPTURE_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()

        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                if (running) onFrame(image.toI420())
            } catch (t: Throwable) {
                onError(t)
            } finally {
                image.close()
            }
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysis,
        )
    }

    fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        provider = null
        analysisExecutor.shutdown()
    }

    private companion object {
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480
    }
}
