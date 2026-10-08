// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.Closeable

/**
 * USBRelay2 (VID 16C0, PID 05DF). El contacto es NC: ON corta los 12 V de la
 * plaqueta y OFF los devuelve. El mismo reporte de 9 bytes que usa el exe.
 */
class UsbRelay(private val hub: UsbHub) : Closeable {
    private var connection: UsbDeviceConnection? = null
    private var deviceId: Int? = null
    private var interfaceNumber: Int = 0
    private val lock = Any()

    enum class Restart { NotCut, Restored, PowerNotRestored }

    fun plugged(): Boolean = hub.relayPlugged()

    fun restart(): Restart = synchronized(lock) {
        val device = hub.relayDevice() ?: return Restart.NotCut
        val link = connectionFor(device) ?: return Restart.NotCut
        if (readStatus(link) == null) return Restart.NotCut
        if (!setRelay(link, relay = 1, enabled = true)) return Restart.NotCut
        Thread.sleep(POWER_OFF_MS)
        if (restorePower(link)) Restart.Restored else Restart.PowerNotRestored
    }

    /**
     * Deja pasar los 12 V. Es idempotente: sirve para reponer la alimentación
     * si un corte anterior no terminó, porque falló el relé o porque la app se
     * cerró en medio de los 5 s.
     */
    fun ensurePowerOn(): Boolean = synchronized(lock) {
        val device = hub.relayDevice() ?: return false
        val link = connectionFor(device) ?: return false
        restorePower(link)
    }

    private fun restorePower(link: UsbDeviceConnection): Boolean {
        repeat(RESTORE_ATTEMPTS) { attempt ->
            if (setRelay(link, relay = 1, enabled = false)) return true
            if (attempt < RESTORE_ATTEMPTS - 1) Thread.sleep(500)
        }
        return false
    }

    private fun connectionFor(device: UsbDevice): UsbDeviceConnection? {
        if (deviceId == device.deviceId && connection != null) return connection
        close()
        val opened = hub.manager.openDevice(device) ?: return null
        val intf = if (device.interfaceCount > 0) device.getInterface(0) else null
        if (intf == null || !opened.claimInterface(intf, true)) {
            opened.close()
            return null
        }
        connection = opened
        deviceId = device.deviceId
        interfaceNumber = intf.id
        return opened
    }

    private fun setRelay(link: UsbDeviceConnection, relay: Int, enabled: Boolean): Boolean {
        val command: Byte = if (enabled) 0xFF.toByte() else 0xFD.toByte()
        val withId = byteArrayOf(0, command, relay.toByte(), 0, 0, 0, 0, 0, 0)
        if (feature(link, withId) >= 0) return true
        val withoutId = byteArrayOf(command, relay.toByte(), 0, 0, 0, 0, 0, 0)
        return feature(link, withoutId) >= 0
    }

    private fun readStatus(link: UsbDeviceConnection): ByteArray? {
        val report = ByteArray(9)
        if (fetch(link, report) >= 0) return report
        val shorter = ByteArray(8)
        return if (fetch(link, shorter) >= 0) shorter else null
    }

    private fun feature(link: UsbDeviceConnection, report: ByteArray): Int =
        link.controlTransfer(0x21, 0x09, 0x0300, interfaceNumber, report, report.size, 2_000)

    private fun fetch(link: UsbDeviceConnection, report: ByteArray): Int =
        link.controlTransfer(0xA1, 0x01, 0x0300, interfaceNumber, report, report.size, 2_000)

    override fun close() {
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        deviceId = null
    }

    companion object {
        const val POWER_OFF_MS = 5_000L
        private const val RESTORE_ATTEMPTS = 3
    }
}
