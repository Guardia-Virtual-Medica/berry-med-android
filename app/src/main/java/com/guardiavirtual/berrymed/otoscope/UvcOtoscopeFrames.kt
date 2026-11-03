// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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
 * libuvc 3.2.7 necesita una superficie para leer la cámara: `startPreview`
 * sólo arranca su hilo de captura si tiene ventana de preview (sin ella
 * registra «UVCCamera::window does not exist…», no lee nada y el callback no
 * corre nunca: 0 cuadros). Por eso se le da la de un [ImageReader] que nadie
 * ve, en RGBX_8888 (el formato en el que libuvc dibuja su ventana) y del
 * tamaño abierto, y se vacía en su propio hilo para que libuvc nunca se trabe
 * esperando un buffer. Los cuadros para LiveKit siguen saliendo del callback
 * NV21. Abrir y cerrar van en [worker], como en el Panel Local.
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
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null

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
        opened.camera.setPreviewDisplay(drain(width, height).surface)
        opened.camera.startPreview()
        log("[TELE] Otoscopio por UVC a ${opened.describe()}, con una superficie que nadie ve")
        return null
    }

    /**
     * La superficie que libuvc exige, consumida apenas llega cada imagen. Si
     * el formato no le cuadra, el error queda en el log una sola vez.
     */
    private fun drain(width: Int, height: Int): ImageReader {
        val thread = HandlerThread("berry-uvc-drain").also { it.start() }
        readerThread = thread
        val drain = ImageReader.newInstance(width, height, PixelFormat.RGBX_8888, DRAIN_IMAGES)
        reader = drain
        var warned = false
        drain.setOnImageAvailableListener(
            { source ->
                try {
                    source.acquireLatestImage()?.close()
                } catch (error: Throwable) {
                    if (!warned) {
                        warned = true
                        log("[TELE] La superficie del otoscopio por UVC no se pudo vaciar: $error")
                    }
                }
            },
            Handler(thread.looper),
        )
        return drain
    }

    override fun stop(done: () -> Unit) {
        closed = true
        worker.execute {
            release()
            done()
        }
    }

    /** En orden: sin callback, la cámara parada y suelta, y recién ahí la superficie y su hilo. No tira nunca. */
    private fun release() {
        try {
            handle?.camera?.setFrameCallback(null, 0)
        } catch (_: Throwable) {
        }
        handle?.release()
        handle = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        try {
            readerThread?.quitSafely()
        } catch (_: Throwable) {
        }
        readerThread = null
    }

    companion object {
        private const val SPARE_FRAMES = 3

        /** Las imágenes de la superficie que nadie ve: dos alcanzan, se sueltan apenas llegan. */
        private const val DRAIN_IMAGES = 2
    }
}
