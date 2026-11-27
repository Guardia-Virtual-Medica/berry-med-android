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
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.i18n.Txt
import com.guardiavirtual.berrymed.i18n.join
import com.guardiavirtual.berrymed.i18n.raw
import com.guardiavirtual.berrymed.i18n.txt

/**
 * Lo que Android ve enchufado, para la pantalla de Testing: los dispositivos
 * USB con sus interfaces, los dispositivos de audio y las cámaras. Sirve para
 * validar el armado del tótem desde Testing: si las placas de audio USB
 * conviven y con qué nombre aparecen, y si el otoscopio aparece como cámara
 * externa.
 * No abre nada: sólo lista. Cada renglón es un [Txt]: datos separados por
 * « · », cada uno una entrada entera o un valor que no se traduce (el
 * identificador USB, el nombre del producto).
 */
class DeviceInventory(context: Context) {
    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameras = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    data class Snapshot(
        val usb: List<Txt>,
        val audio: List<Txt>,
        val cameras: List<Txt>,
        val takenAtNano: Long,
    )

    fun snapshot(): Snapshot = Snapshot(usbLines(), audioLines(), cameraLines(), System.nanoTime())

    private fun usbLines(): List<Txt> =
        usb.deviceList.values.sortedBy { it.deviceName }.map { device ->
            describe(device, usb.hasPermission(device), KnownDevices.name(device.vendorId, device.productId))
        }

    private fun audioLines(): List<Txt> {
        val devices = try {
            audio.getDevices(AudioManager.GET_DEVICES_ALL).toList()
        } catch (error: Exception) {
            return listOf(txt(R.string.testing_inventory_audio_failed, error.toString()))
        }
        return devices
            .sortedWith(compareBy({ !isUsb(it.type) }, { it.type }, { it.id }))
            .map(::describe)
    }

    private fun cameraLines(): List<Txt> {
        return try {
            cameras.cameraIdList.map { id ->
                cameraLine(id, cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING))
            }.ifEmpty { listOf(txt(R.string.testing_inventory_no_cameras)) }
        } catch (error: Exception) {
            listOf(txt(R.string.testing_inventory_cameras_failed, error.toString()))
        }
    }

    companion object {
        private const val SEPARATOR = " · "

        fun describe(device: UsbDevice, permitted: Boolean, known: Txt?): Txt = usbLine(
            vendorId = device.vendorId,
            productId = device.productId,
            known = known,
            names = listOfNotNull(device.manufacturerName, device.productName),
            classes = (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass },
            permitted = permitted,
        )

        /** «04D8:F11B hub YKUSH3 · Yepkit YKUSH3 · HID · permiso sí». */
        fun usbLine(vendorId: Int, productId: Int, known: Txt?, names: List<String>, classes: List<Int>, permitted: Boolean): Txt {
            val id = raw("%04X:%04X".format(vendorId, productId))
            val name = names.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            val classNames = classes.map(::className).distinct()
            return join(
                listOf(
                    if (known != null) join(listOf(id, known), " ") else id,
                    if (name.isBlank()) txt(R.string.testing_inventory_no_name) else raw(name),
                    if (classNames.isEmpty()) txt(R.string.testing_inventory_no_interfaces) else join(classNames, ", "),
                    txt(if (permitted) R.string.testing_inventory_permission_yes else R.string.testing_inventory_permission_no),
                ),
                SEPARATOR,
            )
        }

        fun className(usbClass: Int): Txt = when (usbClass) {
            UsbConstants.USB_CLASS_AUDIO -> txt(R.string.device_usb_class_audio)
            UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA -> txt(R.string.device_usb_class_serial)
            UsbConstants.USB_CLASS_HID -> txt(R.string.device_usb_class_hid)
            UsbConstants.USB_CLASS_MASS_STORAGE -> txt(R.string.device_usb_class_storage)
            UsbConstants.USB_CLASS_HUB -> txt(R.string.device_usb_class_hub)
            UsbConstants.USB_CLASS_VIDEO -> txt(R.string.device_usb_class_video)
            UsbConstants.USB_CLASS_VENDOR_SPEC -> txt(R.string.device_usb_class_vendor)
            else -> txt(R.string.device_usb_class_other, usbClass)
        }

        fun describe(device: AudioDeviceInfo): Txt = audioLine(
            type = device.type,
            name = device.productName.toString(),
            isSource = device.isSource,
            isSink = device.isSink,
            maxRate = device.sampleRates.maxOrNull(),
            maxChannels = device.channelCounts.maxOrNull(),
        )

        /** «auricular USB · USB Audio Device · entrada y salida · 48000 Hz · 2 can.». */
        fun audioLine(type: Int, name: String, isSource: Boolean, isSink: Boolean, maxRate: Int?, maxChannels: Int?): Txt {
            val direction = when {
                isSource && isSink -> R.string.testing_inventory_in_out
                isSource -> R.string.testing_inventory_in
                else -> R.string.testing_inventory_out
            }
            return join(
                listOfNotNull(
                    typeName(type),
                    name.trim().ifBlank { null }?.let(::raw) ?: txt(R.string.testing_inventory_no_name),
                    txt(direction),
                    maxRate?.let { txt(R.string.testing_inventory_hz, it) },
                    maxChannels?.let { txt(R.string.testing_inventory_channels, it) },
                ),
                SEPARATOR,
            )
        }

        /** «cámara 1 · trasera»; [facing] es `LENS_FACING` de Camera2, o null si no lo dice. */
        fun cameraLine(id: String, facing: Int?): Txt = txt(
            when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> R.string.testing_inventory_camera_front
                CameraCharacteristics.LENS_FACING_BACK -> R.string.testing_inventory_camera_back
                CameraCharacteristics.LENS_FACING_EXTERNAL -> R.string.testing_inventory_camera_external
                else -> R.string.testing_inventory_camera_other
            },
            id,
        )

        fun isUsb(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        fun typeName(type: Int): Txt = when (type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> txt(R.string.device_audio_usb_headset)
            AudioDeviceInfo.TYPE_USB_DEVICE -> txt(R.string.device_audio_usb_device)
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> txt(R.string.device_audio_usb_accessory)
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> txt(R.string.device_audio_builtin_mic)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> txt(R.string.device_audio_builtin_speaker)
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> txt(R.string.device_audio_builtin_earpiece)
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> txt(R.string.device_audio_wired_headset)
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> txt(R.string.device_audio_wired_headphones)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> txt(R.string.device_audio_bluetooth)
            AudioDeviceInfo.TYPE_HDMI -> txt(R.string.device_audio_hdmi)
            AudioDeviceInfo.TYPE_TELEPHONY -> txt(R.string.device_audio_telephony)
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> txt(R.string.device_audio_remote_submix)
            else -> txt(R.string.device_audio_other, type)
        }
    }
}
