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

/**
 * Dispositivos USB del tótem: los puertos serie y el hub YKUSH3, con un permiso
 * por dispositivo. También lleva qué puertos ya se probaron y no son la
 * plaqueta: ésos quedan para el termómetro, que sólo habla cuando alguien mide.
 */
class UsbHub(context: Context) {
    private val appContext = context.applicationContext
    val manager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val busy = HashSet<Int>()
    private val notBerry = HashSet<Int>()
    private val requests = PermissionRequests(PERMISSION_GRACE_NANOS)

    fun permissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    /**
     * Pide permiso a los dispositivos del tótem que no lo tienen. No bloquea:
     * lo que ya tiene permiso se usa igual, y un dispositivo ajeno al tótem
     * (un teclado, un lector) no se pide ni frena a los demás.
     */
    fun requestMissingPermissions() {
        val devices = totemDevices()
        val now = System.nanoTime()
        requests.observe(devices.map { it.deviceId }.toSet(), now)
        synchronized(notBerry) { notBerry.retainAll(manager.deviceList.values.map { it.deviceId }.toSet()) }
        for (device in devices) {
            if (manager.hasPermission(device)) continue
            if (requests.shouldAsk(device.deviceId, now)) {
                manager.requestPermission(device, permissionIntent())
            }
        }
    }

    fun forgetAsked() {
        requests.forget()
        requestMissingPermissions()
    }

    fun permissionMissing(): Boolean = totemDevices().any { !manager.hasPermission(it) }

    fun mark(deviceId: Int): Boolean = synchronized(busy) { busy.add(deviceId) }

    fun unmark(deviceId: Int) {
        synchronized(busy) { busy.remove(deviceId) }
    }

    fun markNotBerry(deviceId: Int) {
        synchronized(notBerry) { notBerry.add(deviceId) }
    }

    fun clearNotBerry(deviceId: Int) {
        synchronized(notBerry) { notBerry.remove(deviceId) }
    }

    /** Puertos que todavía pueden ser la plaqueta. */
    fun berryCandidates(): List<UsbSerialDriver> {
        val excluded = synchronized(notBerry) { notBerry.toSet() }
        return serialDrivers().filter { it.device.deviceId !in excluded }
    }

    /** Puertos que el termómetro puede tomar. Ver [thermometerMayTake]. */
    fun thermometerCandidates(berryUp: Boolean): List<UsbSerialDriver> {
        val excluded = synchronized(notBerry) { notBerry.toSet() }
        return serialDrivers().filter { driver ->
            thermometerMayTake(driver.device.vendorId, driver.device.deviceId in excluded, berryUp)
        }
    }

    /** El hub YKUSH3, sólo si ya tiene permiso. */
    fun ykushDevice(): UsbDevice? =
        manager.deviceList.values.firstOrNull { isYkush(it) && manager.hasPermission(it) }

    fun ykushPlugged(): Boolean = manager.deviceList.values.any { isYkush(it) }

    /** Si hay enchufado un dispositivo con ese fabricante y producto, tenga permiso o no. */
    fun plugged(vendorId: Int, productId: Int): Boolean =
        manager.deviceList.values.any { it.vendorId == vendorId && it.productId == productId }

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

    private fun serialDrivers(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).filter { driver ->
            val device = driver.device
            manager.hasPermission(device) && synchronized(busy) { device.deviceId !in busy }
        }
    }

    /**
     * Los puertos serie, el hub YKUSH3 y el otoscopio, con o sin permiso. El
     * otoscopio necesita permiso para el camino UVC del Panel Local, cuando la
     * tablet no lo lista como cámara. El adaptador del estetoscopio y el
     * auricular no: Android los maneja por su cuenta, como audio.
     */
    private fun totemDevices(): List<UsbDevice> {
        val serial = UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { it.device }
        val others = manager.deviceList.values.filter { isYkush(it) || KnownDevices.isOtoscope(it.vendorId, it.productId) }
        return (serial + others).distinctBy { it.deviceId }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.guardiavirtual.berrymed.USB_PERMISSION"
        const val BAUD = 115200

        /** El PM6750 es un STM32 VCP. Un puerto de ST mudo es la plaqueta que todavía no arrancó. */
        const val STM32_VENDOR = 0x0483

        /**
         * Android agrega el dispositivo a la lista un instante antes de darle
         * el permiso a la app predeterminada. Pedirlo en ese hueco deja un
         * diálogo de más en pantalla.
         */
        private const val PERMISSION_GRACE_NANOS = 3L * 1_000_000_000L

        fun isYkush(device: UsbDevice): Boolean = KnownDevices.isYkush3(device.vendorId, device.productId)

        /**
         * El termómetro sólo manda una línea cuando alguien mide, así que no se
         * lo puede reconocer por lo que transmite. Toma un puerto que ya se
         * probó y no es la plaqueta, o cualquiera mientras la plaqueta está
         * conectada, porque hay una sola. Nunca uno de ST: ése es la plaqueta,
         * aunque esté muda, y tiene que quedar libre para cuando arranque.
         */
        fun thermometerMayTake(vendorId: Int, provenNotBerry: Boolean, berryUp: Boolean): Boolean {
            if (vendorId == STM32_VENDOR) return false
            return provenNotBerry || berryUp
        }
    }
}

/**
 * A qué dispositivos ya se les pidió permiso. Cada dispositivo se pide una sola
 * vez: el diálogo aparece arriba de Chrome, que tiene la plataforma médica, y
 * repetirlo solo la taparía una y otra vez. Un rechazo o un diálogo sin
 * respuesta esperan al botón "Pedir permiso USB". Un dispositivo que vuelve a
 * aparecer (otro deviceId, por ejemplo tras desenchufarlo) se pide de nuevo.
 */
class PermissionRequests(private val graceNanos: Long = 0L) {
    private val asked = HashSet<Int>()
    private val firstSeen = HashMap<Int, Long>()

    @Synchronized
    fun shouldAsk(deviceId: Int, now: Long): Boolean {
        val seen = firstSeen.getOrPut(deviceId) { now }
        if (now - seen < graceNanos) return false
        return asked.add(deviceId)
    }

    @Synchronized
    fun forget() {
        asked.clear()
    }

    /** Anota los dispositivos que aparecieron y olvida los que se fueron. */
    @Synchronized
    fun observe(present: Set<Int>, now: Long) {
        for (deviceId in present) firstSeen.putIfAbsent(deviceId, now)
        firstSeen.keys.retainAll(present)
        asked.retainAll(present)
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
