// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import livekit.org.webrtc.NV21Buffer
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor

/**
 * Camino UVC para la teleconsulta: el otoscopio por libuvc, sin vista. libuvc
 * decodifica el MJPEG y entrega cada cuadro en NV21 por su callback; se copia
 * a un arreglo (de un repuesto de tres, para no crear uno por cuadro) y se
 * manda como `NV21Buffer`. Es el camino preferido en la teleconsulta: va
 * directo por USB y no pasa por el servicio de cámaras, así que no compite
 * con la cámara de la tablet que usa Zoom.
 *
 * Sin vista, libuvc no dibuja en ningún lado: sólo convierte y llama al
 * callback. Abrir y cerrar van en [worker], como en el Panel Local.
 */
class UvcOtoscopeFrames(
    context: Context,
    private val probe: OtoscopeProbe,
    private val worker: Executor,
    private val log: (String) -> Unit,
) : OtoscopeFrames {
    override val label = "por UVC"
    private val appContext = context.applicationContext

    // Sólo los toca [worker].
    private var handle: UvcHandle? = null

    @Volatile private var closed = false

    override fun start(sink: (VideoFrame) -> Unit, done: (String?) -> Unit) {
        closed = false
        worker.execute {
            val reason = try {
                open(sink)
            } catch (error: Throwable) {
                release()
                "No se pudo abrir el otoscopio por UVC: ${error.message ?: error.javaClass.simpleName}"
            }
            done(reason)
        }
    }

    private fun open(sink: (VideoFrame) -> Unit): String? {
        if (closed) return "Se apagó mientras abría."
        val opened = when (val result = UvcHandle.open(appContext, probe)) {
            is UvcHandle.Opened.Refused -> return result.reason
            is UvcHandle.Opened.Ok -> result.handle
        }
        handle = opened
        val width = opened.size.width
        val height = opened.size.height
        val bytes = width * height * 3 / 2
        val spare = ArrayBlockingQueue<ByteArray>(SPARE_FRAMES)
        opened.camera.setFrameCallback(
            IFrameCallback { frame: ByteBuffer ->
                // En el hilo de libuvc: una excepción acá no tiene que llegar a la librería nativa.
                try {
                    if (closed || frame.remaining() < bytes) return@IFrameCallback
                    val data = spare.poll() ?: ByteArray(bytes)
                    frame.get(data, 0, bytes)
                    val buffer = NV21Buffer(data, width, height) { spare.offer(data) }
                    val video = VideoFrame(buffer, 0, System.nanoTime())
                    try {
                        sink(video)
                    } finally {
                        video.release()
                    }
                } catch (_: Throwable) {
                }
            },
            UVCCamera.PIXEL_FORMAT_NV21,
        )
        opened.camera.startPreview()
        log("[TELE] Otoscopio por UVC a ${width}×$height MJPEG, sin vista")
        return null
    }

    override fun stop(done: () -> Unit) {
        closed = true
        worker.execute {
            release()
            done()
        }
    }

    private fun release() {
        try {
            handle?.camera?.setFrameCallback(null, 0)
        } catch (_: Throwable) {
        }
        handle?.release()
        handle = null
    }

    companion object {
        private const val SPARE_FRAMES = 3
    }
}
