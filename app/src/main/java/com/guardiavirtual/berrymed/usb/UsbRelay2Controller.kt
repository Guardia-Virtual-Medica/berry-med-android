package com.guardiavirtual.berrymed.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log

/** DCTTech USBRelay2 — VID 16C0 PID 05DF */
class UsbRelay2Controller(
    private val usbManager: UsbManager,
) {
    var isPresent: Boolean = false
        private set

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var interfaceId: Int = 0

    fun bindIfRelay(dev: UsbDevice): Boolean {
        if (dev.vendorId != 0x16C0 || dev.productId != 0x05DF) return false
        device = dev
        return true
    }

    fun open(): Boolean {
        val dev = device ?: return false
        if (!usbManager.hasPermission(dev)) return false
        val conn = usbManager.openDevice(dev) ?: return false
        var hidInterface: android.hardware.usb.UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                hidInterface = iface
                break
            }
        }
        val iface = hidInterface ?: dev.getInterface(0)
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            return false
        }
        connection = conn
        interfaceId = iface.id
        isPresent = true
        return true
    }

    fun close() {
        connection?.close()
        connection = null
        isPresent = false
    }

    /** enabled=true → relay ON (NC open, 12V off). enabled=false → relay OFF (power on). */
    fun setRelay(relayNumber: Int, enabled: Boolean): Boolean {
        val conn = connection ?: return false
        val report = ByteArray(9)
        report[0] = 0x00
        report[1] = if (enabled) 0xFF.toByte() else 0xFD.toByte()
        report[2] = relayNumber.toByte()
        val result = conn.controlTransfer(
            0x21,
            0x09,
            (0x03 shl 8) or 0x00,
            interfaceId,
            report,
            report.size,
            1000,
        )
        if (result < 0) {
            Log.w(TAG, "setRelay failed: $result")
            return false
        }
        return true
    }

    fun restartDevice(relayNumber: Int = WATCHDOG_RELAY, offSeconds: Double = WATCHDOG_POWER_OFF_SEC): Boolean {
        if (!setRelay(relayNumber, true)) return false
        Thread.sleep((offSeconds * 1000).toLong())
        return setRelay(relayNumber, false)
    }

    companion object {
        private const val TAG = "UsbRelay2"
        const val WATCHDOG_RELAY = 1
        const val WATCHDOG_POWER_OFF_SEC = 5.0
    }
}
