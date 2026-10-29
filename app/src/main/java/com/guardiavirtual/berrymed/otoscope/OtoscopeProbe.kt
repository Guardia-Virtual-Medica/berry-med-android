// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import com.guardiavirtual.berrymed.usb.KnownDevices
import com.guardiavirtual.berrymed.usb.UsbHub

/** Si Android ya ve el otoscopio, como cámara externa o como dispositivo USB. Sólo mira, no abre nada. */
class OtoscopeProbe(context: Context, private val hub: UsbHub) {
    private val cameras = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Una cámara externa (USB) en la lista de Camera2. */
    fun cameraSeen(): Boolean = try {
        cameras.cameraIdList.any { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_EXTERNAL
        }
    } catch (_: Exception) {
        false
    }

    fun usbDevice(): UsbDevice? = hub.manager.deviceList.values.firstOrNull {
        KnownDevices.isOtoscope(it.vendorId, it.productId)
    }

    /** Enchufado, tenga permiso o no. */
    fun usbPresent(): Boolean = hub.plugged(KnownDevices.OTOSCOPE_VENDOR, KnownDevices.OTOSCOPE_PRODUCT)

    /** Enchufado y con permiso USB: lo que necesita el camino UVC. */
    fun usbReady(): Boolean = usbDevice()?.let(hub.manager::hasPermission) == true
}
