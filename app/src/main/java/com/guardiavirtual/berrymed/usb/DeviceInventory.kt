// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Lo que Android ve enchufado, para la pantalla de Testing: los dispositivos
 * USB con sus interfaces, los dispositivos de audio y las cámaras. Sirve para
 * comprobar el armado del tótem antes de escribir el Panel Local: si los tres
 * audios USB conviven, y si el otoscopio aparece como cámara externa.
 * No abre nada: sólo lista.
 */
class DeviceInventory(context: Context) {
    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameras = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    data class Snapshot(
        val usb: List<String>,
        val audio: List<String>,
        val cameras: List<String>,
        val takenAtNano: Long,
    )

    fun snapshot(): Snapshot = Snapshot(usbLines(), audioLines(), cameraLines(), System.nanoTime())

    private fun usbLines(): List<String> =
        usb.deviceList.values.sortedBy { it.deviceName }.map { device -> describe(device, usb.hasPermission(device)) }

    private fun audioLines(): List<String> {
        val devices = try {
            audio.getDevices(AudioManager.GET_DEVICES_ALL).toList()
        } catch (error: Exception) {
            return listOf("No se pudo listar el audio: $error")
        }
        return devices
            .sortedWith(compareBy({ !isUsb(it.type) }, { it.type }, { it.id }))
            .map(::describe)
    }

    private fun cameraLines(): List<String> {
        return try {
            cameras.cameraIdList.map { id ->
                val info = cameras.getCameraCharacteristics(id)
                val facing = when (info.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
                    CameraCharacteristics.LENS_FACING_BACK -> "trasera"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "externa (USB)"
                    else -> "?"
                }
                "cámara $id · $facing"
            }.ifEmpty { listOf("Android no lista ninguna cámara") }
        } catch (error: Exception) {
            listOf("No se pudo listar las cámaras: $error")
        }
    }

    companion object {
        fun describe(device: UsbDevice, permitted: Boolean): String {
            val known = KnownDevices.name(device.vendorId, device.productId)
            val name = listOfNotNull(device.manufacturerName?.trim(), device.productName?.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifBlank { "sin nombre" }
            val classes = (0 until device.interfaceCount)
                .map { className(device.getInterface(it).interfaceClass) }
                .distinct()
                .joinToString(", ")
                .ifBlank { "sin interfaces" }
            return buildString {
                append("%04X:%04X".format(device.vendorId, device.productId))
                if (known != null) append(" ").append(known)
                append(" · ").append(name)
                append(" · ").append(classes)
                append(" · permiso ").append(if (permitted) "sí" else "no")
            }
        }

        fun className(usbClass: Int): String = when (usbClass) {
            UsbConstants.USB_CLASS_AUDIO -> "audio"
            UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA -> "serie"
            UsbConstants.USB_CLASS_HID -> "HID"
            UsbConstants.USB_CLASS_MASS_STORAGE -> "almacenamiento"
            UsbConstants.USB_CLASS_HUB -> "hub"
            UsbConstants.USB_CLASS_VIDEO -> "video"
            UsbConstants.USB_CLASS_VENDOR_SPEC -> "propietaria"
            else -> "clase $usbClass"
        }

        fun describe(device: AudioDeviceInfo): String {
            val direction = when {
                device.isSource && device.isSink -> "entrada y salida"
                device.isSource -> "entrada"
                else -> "salida"
            }
            val rates = device.sampleRates.takeIf { it.isNotEmpty() }?.let { " · ${it.max()} Hz" }.orEmpty()
            val channels = device.channelCounts.takeIf { it.isNotEmpty() }?.let { " · ${it.max()} can." }.orEmpty()
            return "${typeName(device.type)} · ${device.productName.toString().trim().ifBlank { "sin nombre" }} · $direction$rates$channels"
        }

        fun isUsb(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> "auricular USB"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "audio USB"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "accesorio USB"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micrófono de la tablet"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "parlante de la tablet"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "auricular interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "auricular con cable (jack)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "auriculares con cable (jack)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telefonía"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "mezcla interna"
            else -> "tipo $type"
        }
    }
}
