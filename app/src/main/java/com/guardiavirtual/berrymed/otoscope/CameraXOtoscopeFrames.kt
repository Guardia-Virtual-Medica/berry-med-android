// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camino Camera2 para la teleconsulta: el otoscopio como cámara externa con
 * CameraX, sin vista. Un `ImageAnalysis` en YUV_420_888 entrega cada cuadro y
 * se copia a I420 para WebRTC. Como no hay pantalla, el ciclo de vida es
 * propio ([Owner]): se prende al abrir y se destruye al cerrar.
 */
class CameraXOtoscopeFrames(
    context: Context,
    private val log: (String) -> Unit,
) : OtoscopeFrames {
    override val label = "por Camera2"
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Sólo en el hilo de la pantalla.
    private var owner: Owner? = null
    private var provider: ProcessCameraProvider? = null
    private var analyzer: ExecutorService? = null

    @Volatile private var closed = false

    override fun start(sink: (VideoFrame) -> Unit, done: (String?) -> Unit) {
        closed = false
        main.post {
            if (closed) {
                done("Se apagó mientras abría.")
                return@post
            }
            val lifecycle = Owner().also { it.resume() }
            owner = lifecycle
            val thread = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "berry-tele-oto").apply { isDaemon = true } }
            analyzer = thread
            ExternalCamera(appContext).bind(
                owner = lifecycle,
                useCase = {
                    ImageAnalysis.Builder()
                        .setResolutionSelector(ExternalCamera.RESOLUTION)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            var logged = false
                            analysis.setAnalyzer(thread) { image ->
                                try {
                                    if (!closed) {
                                        if (!logged) {
                                            logged = true
                                            log("[TELE] Otoscopio por Camera2 a ${image.width}×${image.height}, sin vista")
                                        }
                                        val frame = toFrame(image)
                                        try {
                                            sink(frame)
                                        } finally {
                                            frame.release()
                                        }
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    image.close()
                                }
                            }
                        }
                },
                closed = { closed },
            ) { opened, reason ->
                provider = opened
                if (reason != null) release()
                done(reason)
            }
        }
    }

    override fun stop(done: () -> Unit) {
        closed = true
        main.post {
            release()
            done()
        }
    }

    /** En el hilo de la pantalla. */
    private fun release() {
        provider?.unbindAll()
        provider = null
        owner?.destroy()
        owner = null
        analyzer?.shutdown()
        analyzer = null
    }

    /** El ciclo de vida de la cámara sin pantalla. Se maneja desde el hilo de la pantalla. */
    private class Owner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        /** YUV_420_888 (con cualquier paso entre píxeles) a I420, que es lo que come WebRTC. */
        fun toFrame(image: ImageProxy): VideoFrame {
            val width = image.width
            val height = image.height
            val buffer = JavaI420Buffer.allocate(width, height)
            val planes = image.planes
            copyPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, buffer.dataY, buffer.strideY, width, height)
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            copyPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, buffer.dataU, buffer.strideU, chromaWidth, chromaHeight)
            copyPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, buffer.dataV, buffer.strideV, chromaWidth, chromaHeight)
            val timestamp = image.imageInfo.timestamp.takeIf { it > 0 } ?: System.nanoTime()
            return VideoFrame(buffer, image.imageInfo.rotationDegrees, timestamp)
        }

        /** Copia fila por fila en bloque; con paso 2 (croma intercalado) toma un byte de cada dos de la fila leída. */
        private fun copyPlane(
            src: ByteBuffer,
            srcRowStride: Int,
            srcPixelStride: Int,
            dst: ByteBuffer,
            dstRowStride: Int,
            width: Int,
            height: Int,
        ) {
            val rowBytes = (width - 1) * srcPixelStride + 1
            val raw = ByteArray(rowBytes)
            val row = ByteArray(width)
            for (y in 0 until height) {
                val start = y * srcRowStride
                val available = minOf(rowBytes, src.limit() - start)
                if (available <= 0) break
                src.position(start)
                src.get(raw, 0, available)
                if (srcPixelStride == 1) {
                    dst.position(y * dstRowStride)
                    dst.put(raw, 0, minOf(width, available))
                } else {
                    for (x in 0 until width) {
                        val index = x * srcPixelStride
                        row[x] = if (index < available) raw[index] else 0
                    }
                    dst.position(y * dstRowStride)
                    dst.put(row, 0, width)
                }
            }
            dst.rewind()
        }
    }
}
