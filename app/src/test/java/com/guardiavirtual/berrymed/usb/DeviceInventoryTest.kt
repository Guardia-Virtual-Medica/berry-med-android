// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.hardware.camera2.CameraCharacteristics
import android.hardware.usb.UsbConstants
import android.media.AudioDeviceInfo
import com.guardiavirtual.berrymed.i18n.es
import kotlin.test.Test
import kotlin.test.assertEquals

/** Los renglones de Testing → Dispositivos que ve Android, como se leían antes de pasar al diccionario. */
class DeviceInventoryTest {
    @Test
    fun aKnownUsbDeviceWithItsInterfaces() {
        val line = DeviceInventory.usbLine(
            vendorId = KnownDevices.YKUSH_VENDOR,
            productId = KnownDevices.YKUSH3_PRODUCT,
            known = KnownDevices.name(KnownDevices.YKUSH_VENDOR, KnownDevices.YKUSH3_PRODUCT),
            names = listOf(" Yepkit ", "YKUSH3"),
            classes = listOf(UsbConstants.USB_CLASS_HID, UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA, 0xEF),
            permitted = true,
        )
        assertEquals("04D8:F11B hub YKUSH3 · Yepkit YKUSH3 · HID, serie, clase 239 · permiso sí", es(line))
    }

    @Test
    fun anUnknownUsbDeviceWithoutNameNorInterfaces() {
        val line = DeviceInventory.usbLine(0x1234, 0xABCD, null, listOf("", " "), emptyList(), permitted = false)
        assertEquals("1234:ABCD · sin nombre · sin interfaces · permiso no", es(line))
    }

    @Test
    fun audioLines() {
        assertEquals(
            "auricular USB · USB Audio Device · entrada y salida · 48000 Hz · 2 can.",
            es(DeviceInventory.audioLine(AudioDeviceInfo.TYPE_USB_HEADSET, "USB Audio Device", true, true, 48000, 2)),
        )
        assertEquals(
            "parlante de la tablet · sdk_gphone64_arm64 · salida",
            es(DeviceInventory.audioLine(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "sdk_gphone64_arm64", false, true, null, null)),
        )
        assertEquals(
            "tipo 16 · sin nombre · entrada · 2 can.",
            es(DeviceInventory.audioLine(16, " ", true, false, null, 2)),
        )
        assertEquals("Bluetooth", es(DeviceInventory.typeName(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        assertEquals("mezcla interna", es(DeviceInventory.typeName(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)))
    }

    @Test
    fun cameraLines() {
        assertEquals("cámara 1 · trasera", es(DeviceInventory.cameraLine("1", CameraCharacteristics.LENS_FACING_BACK)))
        assertEquals("cámara 10 · externa (USB)", es(DeviceInventory.cameraLine("10", CameraCharacteristics.LENS_FACING_EXTERNAL)))
        assertEquals("cámara 0 · ?", es(DeviceInventory.cameraLine("0", null)))
    }
}
