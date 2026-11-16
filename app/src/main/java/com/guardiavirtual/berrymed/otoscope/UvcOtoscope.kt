// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.util.concurrent.Executor

/**
 * Camino UVC: la tablet no lista el otoscopio como cámara, así que se le
 * habla directo por USB con libuvc (módulo de AndroidUSBCamera). Pide MJPEG
 * 800×800 a 30 cuadros y lo dibuja en un `TextureView` de la tarjeta. Cada
 * cuadro dibujado avisa a la vista (`onSurfaceTextureUpdated`): ahí se cuentan
 * los cuadros por segundo que se ven. El
 * micrófono y el almacenamiento del otoscopio no se tocan: libuvc sólo
 * reclama las interfaces de video.
 *
 * El permiso USB lo da Android por el filtro de dispositivos, como a la
 * plaqueta. No se usa el registro de dispositivos de la librería: pide el
 * permiso con un PendingIntent que en Android 14 no recibe la respuesta.
 *
 * Abrir y cerrar bloquean (transferencias de control USB) y van en [worker],
 * el hilo del otoscopio en el servicio: así el puerto se apaga siempre
 * después de soltar la cámara.
 *
 * El cuadro del otoscopio se muda de lugar al ampliarlo. Al sacar el
 * `TextureView` de la ventana Android destruye su `SurfaceTexture` y al
 * volver a ponerlo crea otro, pero libuvc sigue dibujando en el primero:
 * por eso, mientras está abierto, se guarda el primero, no se deja que la
 * vista lo libere y se le vuelve a poner cuando reaparece. libuvc ni se entera.
 *
 * Mientras está abierto se mira una vez por segundo si el otoscopio se
 * reconectó (pasa al elegir «Computer» en su pantalla, [OtoscopeReconnect])
 * y, si se reconectó, se suelta y se abre de nuevo.
 */
class UvcOtoscope(
    context: Context,
    private val textureView: TextureView,
    private val probe: OtoscopeProbe,
    private val worker: Executor,
    private val log: (String) -> Unit,
) : OtoscopeSource {
    override val label = "por UVC"
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Sólo los toca [worker].
    private var handle: UvcHandle? = null
    private var surface: Surface? = null

    @Volatile private var closed = false

    /** El `deviceId` del otoscopio abierto (o el que se está por abrir). */
    @Volatile private var openedId: Int? = null

    /** El `SurfaceTexture` en el que dibuja libuvc. Sólo lo toca el hilo de la pantalla. */
    private var kept: SurfaceTexture? = null

    /** Cuenta los cuadros dibujados. Sólo lo toca el hilo de la pantalla. */
    private var meter = OtoscopeFps.Meter()

    /** La vigilancia de la reconexión; null si no está corriendo. Sólo lo toca el hilo de la pantalla. */
    private var watch: Runnable? = null

    /** Cuándo se dibujó el último cuadro, o cuándo se abrió si todavía no hubo ninguno. Sólo lo toca el hilo de la pantalla. */
    private var aliveAt = System.nanoTime()

    /** Las veces seguidas que se reabrió por falta de imagen ([OtoscopeReconnect.stallMs]). Sólo lo toca el hilo de la pantalla. */
    private var stalls = 0

    /** Los cuadros dibujados desde que se abrió o se reabrió. Sólo lo toca el hilo de la pantalla. */
    private var framesSinceOpen = 0

    override fun open(done: (String?) -> Unit, onRate: (Double) -> Unit) {
        closed = false
        kept = null
        meter = OtoscopeFps.Meter()
        textureView.visibility = View.VISIBLE
        // Queda puesto hasta close(): atiende la primera superficie y también las que vengan al mudar la vista.
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val previous = kept
                when {
                    previous == null -> if (!closed) use(texture, done)
                    // setSurfaceTexture libera solo el nuevo, que no llegó a usarse.
                    previous !== texture && !previous.isReleased -> textureView.setSurfaceTexture(previous)
                }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            // El guardado no lo libera la vista: lo sigue usando libuvc, y lo suelta close().
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = texture !== kept

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (closed) return
                aliveAt = System.nanoTime()
                if (++framesSinceOpen == OtoscopeReconnect.HEALTHY_FRAMES) stalls = 0
                meter.frame(aliveAt)?.let(onRate)
            }
        }
        val texture = textureView.surfaceTexture
        // Si no, la superficie existe recién cuando la vista se dibuja por primera vez y llega por el listener.
        if (textureView.isAvailable && texture != null) use(texture, done)
    }

    private fun use(texture: SurfaceTexture, done: (String?) -> Unit) {
        kept = texture
        start(texture, done)
    }

    private fun start(texture: SurfaceTexture, done: (String?) -> Unit) {
        worker.execute {
            val reason = if (closed) {
                null
            } else {
                try {
                    openCamera(texture)
                } catch (error: Throwable) {
                    // Throwable: si la librería nativa no carga es un Error, no una Exception.
                    release()
                    "No se pudo abrir el otoscopio por UVC: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            main.post {
                if (closed) return@post
                if (reason == null) watchReconnect(texture)
                done(reason)
            }
        }
    }

    /**
     * Una vez por segundo: si Android ve el otoscopio con otro `deviceId` que
     * el que se abrió (se reconectó, lo que pasa al elegir «Computer»), o es
     * el mismo pero dejó de mandar imagen, se espera [OtoscopeReconnect.SETTLE_MS]
     * a que termine de presentarse y se abre de nuevo.
     */
    private fun watchReconnect(texture: SurfaceTexture) {
        watch?.let(main::removeCallbacks)
        aliveAt = System.nanoTime()
        stalls = 0
        framesSinceOpen = 0
        val tick = object : Runnable {
            override fun run() {
                if (closed) return
                val opened = openedId
                val now = probe.usbDevice()?.deviceId
                val quietMs = (System.nanoTime() - aliveAt) / 1_000_000L
                if (OtoscopeReconnect.shouldReopen(opened, now, quietMs, stalls)) {
                    val why = OtoscopeReconnect.why(opened, now)
                    stalls = if (now != opened) 0 else stalls + 1
                    framesSinceOpen = 0
                    openedId = now
                    aliveAt = System.nanoTime() + OtoscopeReconnect.SETTLE_MS * 1_000_000L
                    main.postDelayed({ reopen(texture, why) }, OtoscopeReconnect.SETTLE_MS)
                }
                main.postDelayed(this, WATCH_MS)
            }
        }
        watch = tick
        main.postDelayed(tick, WATCH_MS)
    }

    private fun reopen(texture: SurfaceTexture, why: String) {
        worker.execute {
            if (closed) return@execute
            log("[OTOSCOPIO] El otoscopio $why: se abre de nuevo")
            release()
            try {
                openCamera(texture)?.let { log("[OTOSCOPIO] $it") }
            } catch (error: Throwable) {
                release()
                log("[OTOSCOPIO] No se pudo reabrir por UVC: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun openCamera(texture: SurfaceTexture): String? {
        val opened = when (val result = UvcHandle.open(appContext, probe)) {
            is UvcHandle.Opened.Refused -> return result.reason
            is UvcHandle.Opened.Ok -> result.handle
        }
        handle = opened
        openedId = opened.deviceId
        val size = opened.size
        texture.setDefaultBufferSize(size.width, size.height)
        val target = Surface(texture)
        surface = target
        opened.camera.setPreviewDisplay(target)
        opened.camera.startPreview()
        log("[OTOSCOPIO] Imagen por UVC a ${opened.describe()}")
        return null
    }

    override fun close(done: () -> Unit) {
        closed = true
        watch?.let(main::removeCallbacks)
        watch = null
        textureView.surfaceTextureListener = null
        // Si la vista lo tiene puesto, ahora es de ella y lo libera al irse. Si no (estaba
        // fuera de la ventana o ya se dibujó con otro), nadie más lo suelta: va después de libuvc.
        val orphan = kept?.takeIf { it !== textureView.surfaceTexture }
        kept = null
        worker.execute {
            release()
            orphan?.release()
            main.post { if (closed) textureView.visibility = View.GONE }
            done()
        }
    }

    private fun release() {
        handle?.release()
        handle = null
        surface?.release()
        surface = null
    }

    override fun snapshot(): Bitmap? = textureView.bitmap

    private companion object {
        /** Cada cuánto se mira si el otoscopio se reconectó. */
        const val WATCH_MS = 1_000L
    }
}
