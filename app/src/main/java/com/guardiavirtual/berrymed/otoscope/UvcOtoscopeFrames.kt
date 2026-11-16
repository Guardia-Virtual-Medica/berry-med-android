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
import kotlin.concurrent.thread

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
 * ve, en RGBA_8888 (el formato en el que libuvc dibuja su ventana: con
 * RGBX_8888 cada imagen tiraba «buffer format 0x1 doesn't match… 0x2», no se
 * consumía y libuvc se quedaba sin lugar) y del tamaño abierto, y se vacía en
 * su propio hilo para que libuvc nunca se trabe esperando un buffer. Los
 * cuadros para LiveKit siguen saliendo del callback NV21. Abrir y cerrar van
 * en [worker], como en el Panel Local.
 *
 * Mientras está abierto, un hilo propio mira una vez por segundo si el
 * otoscopio se reconectó (al elegir «Computer») o dejó de mandar cuadros
 * ([OtoscopeReconnect]), y en ese caso lo abre de nuevo con el mismo destino:
 * también después de publicado, para que al médico no le quede la imagen congelada.
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
    private var sink: (VideoFrame) -> Unit = {}

    /** El `deviceId` del otoscopio abierto (o el que se está por abrir); null si no abrió. */
    @Volatile var openedId: Int? = null
        private set

    /** Cuándo llegó el último cuadro, o cuándo se abrió si todavía no llegó ninguno (`nanoTime`). */
    @Volatile private var aliveAt = System.nanoTime()

    /** Las veces seguidas que se reabrió por falta de imagen ([OtoscopeReconnect.stallMs]). */
    @Volatile private var stalls = 0

    /** Los cuadros que llegaron desde que se abrió. Sólo lo toca el hilo de libuvc (y [open] lo pone en cero). */
    @Volatile private var framesSinceOpen = 0
    private var watcher: Thread? = null

    override fun start(sink: (VideoFrame) -> Unit, done: (String?) -> Unit) {
        closed = false
        this.sink = sink
        worker.execute {
            val reason = try {
                open(sink)
            } catch (error: Throwable) {
                release()
                "No se pudo abrir el otoscopio por UVC: ${error.message ?: error.javaClass.simpleName}"
            }
            done(reason)
        }
        watcher = thread(name = "berry-uvc-watch", isDaemon = true) {
            while (!closed) {
                try {
                    Thread.sleep(WATCH_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val opened = openedId
                val now = probe.usbDevice()?.deviceId
                val quietMs = (System.nanoTime() - aliveAt) / 1_000_000L
                if (!closed && now != null && OtoscopeReconnect.shouldReopen(opened, now, quietMs, stalls)) {
                    stalls = if (now != opened) 0 else stalls + 1
                    reopen(now, OtoscopeReconnect.why(opened, now))
                }
            }
        }
    }

    private fun open(sink: (VideoFrame) -> Unit): String? {
        if (closed) return "Se apagó mientras abría."
        val opened = when (val result = UvcHandle.open(appContext, probe)) {
            is UvcHandle.Opened.Refused -> return result.reason
            is UvcHandle.Opened.Ok -> result.handle
        }
        handle = opened
        openedId = opened.deviceId
        aliveAt = System.nanoTime()
        framesSinceOpen = 0
        val width = opened.size.width
        val height = opened.size.height
        val bytes = width * height * 3 / 2
        val spare = ArrayBlockingQueue<ByteArray>(SPARE_FRAMES)
        opened.camera.setFrameCallback(
            IFrameCallback { frame: ByteBuffer ->
                // En el hilo de libuvc: una excepción acá no tiene que llegar a la librería nativa.
                try {
                    if (closed || frame.remaining() < bytes) return@IFrameCallback
                    aliveAt = System.nanoTime()
                    if (++framesSinceOpen == OtoscopeReconnect.HEALTHY_FRAMES) stalls = 0
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
        val drain = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, DRAIN_IMAGES)
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

    /**
     * El otoscopio se reconectó como [deviceId] o dejó de mandar cuadros
     * ([why]): después de [OtoscopeReconnect.SETTLE_MS] se suelta lo abierto y
     * se abre de nuevo, con el mismo destino de cuadros. Anota el `deviceId` y
     * el momento ya, para no pedirlo dos veces.
     */
    private fun reopen(deviceId: Int, why: String) {
        openedId = deviceId
        aliveAt = System.nanoTime()
        worker.execute {
            try {
                Thread.sleep(OtoscopeReconnect.SETTLE_MS)
            } catch (_: InterruptedException) {
                return@execute
            }
            if (closed) return@execute
            log("[TELE] El otoscopio $why: se abre de nuevo")
            release()
            try {
                open(sink)?.let { log("[TELE] $it") }
                aliveAt = System.nanoTime()
            } catch (error: Throwable) {
                release()
                log("[TELE] No se pudo reabrir el otoscopio por UVC: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun stop(done: () -> Unit) {
        closed = true
        watcher?.interrupt()
        watcher = null
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

        /** Cada cuánto se mira si el otoscopio se reconectó o dejó de mandar cuadros. */
        private const val WATCH_MS = 1_000L

        /** Las imágenes de la superficie que nadie ve: dos alcanzan, se sueltan apenas llegan. */
        private const val DRAIN_IMAGES = 2
    }
}
