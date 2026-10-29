// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.util.concurrent.Executor

/**
 * Camino UVC: la tablet no lista el otoscopio como cámara, así que se le
 * habla directo por USB con libuvc (módulo de AndroidUSBCamera). Pide MJPEG
 * 800×800 a 30 cuadros y lo dibuja en un `TextureView` de la tarjeta. El
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
    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var surface: Surface? = null

    @Volatile private var closed = false

    override fun isAvailable(): Boolean = probe.usbReady()

    override fun open(done: (String?) -> Unit) {
        closed = false
        textureView.visibility = View.VISIBLE
        val texture = textureView.surfaceTexture
        if (textureView.isAvailable && texture != null) {
            start(texture, done)
            return
        }
        // La superficie existe recién cuando la vista se dibuja por primera vez.
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                textureView.surfaceTextureListener = null
                if (!closed) start(texture, done)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
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
            main.post { if (!closed) done(reason) }
        }
    }

    private fun openCamera(texture: SurfaceTexture): String? {
        val device: UsbDevice = probe.usbDevice() ?: return "El otoscopio no aparece por USB."
        if (!probe.usbReady()) return "Falta el permiso USB del otoscopio: tocá «Pedir permiso USB» en Berry Med."
        val usb = USBMonitor(appContext, IGNORE_EVENTS)
        monitor = usb
        val uvc = UVCCamera()
        camera = uvc
        uvc.open(usb.openDevice(device))
        val offered = uvc.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG).orEmpty()
            .map { OtoscopeResolution.Size(it.width, it.height) }
        val size = OtoscopeResolution.best(offered)
            ?: OtoscopeResolution.Size(OtoscopeResolution.TARGET, OtoscopeResolution.TARGET)
        try {
            uvc.setPreviewSize(size.width, size.height, MIN_FPS, MAX_FPS, UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH)
        } catch (_: IllegalArgumentException) {
            // Algunas cámaras no aceptan el rango de cuadros: se deja que elija ella.
            uvc.setPreviewSize(size.width, size.height, UVCCamera.FRAME_FORMAT_MJPEG)
        }
        texture.setDefaultBufferSize(size.width, size.height)
        val target = Surface(texture)
        surface = target
        uvc.setPreviewDisplay(target)
        uvc.startPreview()
        log("[OTOSCOPIO] Imagen por UVC a ${size.width}×${size.height} MJPEG")
        return null
    }

    override fun close(done: () -> Unit) {
        closed = true
        textureView.surfaceTextureListener = null
        worker.execute {
            release()
            main.post { if (closed) textureView.visibility = View.GONE }
            done()
        }
    }

    private fun release() {
        try {
            camera?.stopPreview()
        } catch (_: Throwable) {
        }
        try {
            camera?.destroy()
        } catch (_: Throwable) {
        }
        camera = null
        try {
            monitor?.destroy()
        } catch (_: Throwable) {
        }
        monitor = null
        surface?.release()
        surface = null
    }

    override fun snapshot(): Bitmap? = textureView.bitmap

    companion object {
        private const val MIN_FPS = 1
        private const val MAX_FPS = 30

        /** El registro de la librería no se usa (ver arriba): sus avisos no llegan nunca. */
        private val IGNORE_EVENTS = object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) = Unit

            override fun onDetach(device: UsbDevice?) = Unit

            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) = Unit

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) = Unit

            override fun onCancel(device: UsbDevice?) = Unit
        }
    }
}
