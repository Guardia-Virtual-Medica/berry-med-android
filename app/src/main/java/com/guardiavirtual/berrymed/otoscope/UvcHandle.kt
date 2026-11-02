// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.hardware.usb.UsbDevice
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

/**
 * El otoscopio abierto por libuvc, en MJPEG al tamaño más cercano a 800×800
 * y a 30 cuadros si los ofrece ([OtoscopeFps]), todavía sin destino para la imagen. Lo comparten la tarjeta del Panel Local
 * ([UvcOtoscope], que dibuja en una vista) y la teleconsulta
 * ([UvcOtoscopeFrames], que recibe cuadros). Abrir y soltar bloquean
 * (transferencias de control USB): van en el hilo del otoscopio.
 */
internal class UvcHandle private constructor(
    private val monitor: USBMonitor,
    val camera: UVCCamera,
    val size: OtoscopeResolution.Size,
    /** Los cuadros por segundo pedidos, o null si la cadencia la eligió la cámara. */
    val fps: Int?,
) {
    /** Cómo se abrió, para el log: "800×800 MJPEG, 30 cuadros/s". */
    fun describe(): String {
        val rate = if (fps != null) "$fps cuadros/s" else "cadencia a elección de la cámara"
        return "${size.width}×${size.height} MJPEG, $rate"
    }

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
                val fps = setPreviewSize(camera, size)
                return Opened.Ok(UvcHandle(usb, camera, size, fps))
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

        /**
         * Pide el tamaño con cada rango de [OtoscopeFps.uvcAttempts] en orden:
         * libuvc tira `IllegalArgumentException` si la cámara no declara ningún
         * intervalo dentro del rango. Si ninguno entra, se pide sin rango, como
         * antes (por dentro, de 1 a 31). Devuelve la cadencia para el log según
         * [OtoscopeFps.reported] (null si quedó a elección de la cámara);
         * libuvc no dice cuál negoció.
         */
        private fun setPreviewSize(camera: UVCCamera, size: OtoscopeResolution.Size): Int? {
            for (range in OtoscopeFps.uvcAttempts()) {
                try {
                    camera.setPreviewSize(
                        size.width,
                        size.height,
                        range.first,
                        range.last,
                        UVCCamera.FRAME_FORMAT_MJPEG,
                        UVCCamera.DEFAULT_BANDWIDTH,
                    )
                    return OtoscopeFps.reported(range)
                } catch (_: IllegalArgumentException) {
                }
            }
            camera.setPreviewSize(size.width, size.height, UVCCamera.FRAME_FORMAT_MJPEG)
            return null
        }

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
