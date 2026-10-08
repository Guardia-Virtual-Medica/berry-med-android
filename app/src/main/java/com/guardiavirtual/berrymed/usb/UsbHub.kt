// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/** Dispositivos USB del tótem: puertos serie y el relé, con un permiso por dispositivo. */
class UsbHub(context: Context) {
    private val appContext = context.applicationContext
    val manager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val busy = HashSet<Int>()
    private val asked = HashSet<Int>()

    fun permissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    /** Pide permiso una vez por dispositivo. Devuelve false si todavía falta alguno. */
    fun ensurePermissions(): Boolean {
        var ready = true
        for (device in manager.deviceList.values) {
            if (manager.hasPermission(device)) {
                synchronized(asked) { asked.remove(device.deviceId) }
                continue
            }
            ready = false
            val firstTime = synchronized(asked) { asked.add(device.deviceId) }
            if (firstTime) manager.requestPermission(device, permissionIntent())
        }
        return ready
    }

    fun forgetAsked() {
        synchronized(asked) { asked.clear() }
        ensurePermissions()
    }

    fun permissionMissing(): Boolean = manager.deviceList.values.any { !manager.hasPermission(it) }

    fun mark(deviceId: Int): Boolean = synchronized(busy) { busy.add(deviceId) }

    fun unmark(deviceId: Int) {
        synchronized(busy) { busy.remove(deviceId) }
    }

    fun serialDrivers(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).filter { driver ->
            val device = driver.device
            !isRelay(device) && manager.hasPermission(device) && synchronized(busy) { device.deviceId !in busy }
        }
    }

    fun relayDevice(): UsbDevice? =
        manager.deviceList.values.firstOrNull { isRelay(it) && manager.hasPermission(it) }

    fun relayPlugged(): Boolean = manager.deviceList.values.any { isRelay(it) }

    fun isPresent(device: UsbDevice): Boolean =
        manager.deviceList.values.any { it.deviceId == device.deviceId }

    fun open(driver: UsbSerialDriver): SerialHandle? {
        val connection = manager.openDevice(driver.device) ?: return null
        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            return null
        }
        return try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            SerialHandle(driver.device, connection, port)
        } catch (_: Exception) {
            try {
                port.close()
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.guardiavirtual.berrymed.USB_PERMISSION"
        const val BAUD = 115200
        const val RELAY_VENDOR = 0x16C0
        const val RELAY_PRODUCT = 0x05DF

        fun isRelay(device: UsbDevice): Boolean =
            device.vendorId == RELAY_VENDOR && device.productId == RELAY_PRODUCT
    }
}

class SerialHandle(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    val port: UsbSerialPort,
) {
    fun write(bytes: ByteArray) {
        port.write(bytes, 1_000)
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)

    fun close() {
        try {
            port.close()
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}
