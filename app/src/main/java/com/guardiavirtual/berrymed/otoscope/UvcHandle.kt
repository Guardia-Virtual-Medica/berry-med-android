// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.hardware.usb.UsbDevice
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

/**
 * El otoscopio abierto por libuvc, en MJPEG al tamaño más cercano a 800×800,
 * todavía sin destino para la imagen. Lo comparten la tarjeta del Panel Local
 * ([UvcOtoscope], que dibuja en una vista) y la teleconsulta
 * ([UvcOtoscopeFrames], que recibe cuadros). Abrir y soltar bloquean
 * (transferencias de control USB): van en el hilo del otoscopio.
 */
internal class UvcHandle private constructor(
    private val monitor: USBMonitor,
    val camera: UVCCamera,
    val size: OtoscopeResolution.Size,
) {
    /** Para la imagen y suelta la cámara y el USB. No tira nunca. */
    fun release() {
        try {
            camera.stopPreview()
        } catch (_: Throwable) {
        }
        try {
            camera.destroy()
        } catch (_: Throwable) {
        }
        try {
            monitor.destroy()
        } catch (_: Throwable) {
        }
    }

    sealed interface Opened {
        data class Ok(val handle: UvcHandle) : Opened

        /** No se intentó: el otoscopio no está o falta su permiso USB. */
        data class Refused(val reason: String) : Opened
    }

    companion object {
        /**
         * Abre el otoscopio. Si libuvc falla a mitad de camino, suelta lo que
         * abrió y tira el error (Throwable: si la librería nativa no carga es un Error).
         */
        fun open(context: Context, probe: OtoscopeProbe): Opened {
            val device: UsbDevice = probe.usbDevice() ?: return Opened.Refused("El otoscopio no aparece por USB.")
            if (!probe.usbReady()) {
                return Opened.Refused("Falta el permiso USB del otoscopio: tocá «Pedir permiso USB» en Berry Med.")
            }
            val usb = USBMonitor(context.applicationContext, IGNORE_EVENTS)
            var uvc: UVCCamera? = null
            try {
                val camera = UVCCamera()
                uvc = camera
                camera.open(usb.openDevice(device))
                val offered = camera.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG).orEmpty()
                    .map { OtoscopeResolution.Size(it.width, it.height) }
                val size = OtoscopeResolution.best(offered)
                    ?: OtoscopeResolution.Size(OtoscopeResolution.TARGET, OtoscopeResolution.TARGET)
                try {
                    camera.setPreviewSize(size.width, size.height, MIN_FPS, MAX_FPS, UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH)
                } catch (_: IllegalArgumentException) {
                    // Algunas cámaras no aceptan el rango de cuadros: se deja que elija ella.
                    camera.setPreviewSize(size.width, size.height, UVCCamera.FRAME_FORMAT_MJPEG)
                }
                return Opened.Ok(UvcHandle(usb, camera, size))
            } catch (error: Throwable) {
                try {
                    uvc?.destroy()
                } catch (_: Throwable) {
                }
                try {
                    usb.destroy()
                } catch (_: Throwable) {
                }
                throw error
            }
        }

        private const val MIN_FPS = 1
        private const val MAX_FPS = 30

        /** El registro de la librería no se usa (ver [UvcOtoscope]): sus avisos no llegan nunca. */
        private val IGNORE_EVENTS = object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) = Unit

            override fun onDetach(device: UsbDevice?) = Unit

            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) = Unit

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) = Unit

            override fun onCancel(device: UsbDevice?) = Unit
        }
    }
}
