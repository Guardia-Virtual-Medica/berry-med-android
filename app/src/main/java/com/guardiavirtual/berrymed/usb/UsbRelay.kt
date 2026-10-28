// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.Closeable

/**
 * USBRelay2 (VID 16C0, PID 05DF). El contacto es NC: ON corta los 12 V de la
 * plaqueta y OFF los devuelve.
 *
 * El exe arma 9 bytes con un report ID 0 adelante, pero Windows saca ese byte
 * antes de mandarlo: al relé le llegan 8, `[cmd, relé, 0…]`. Acá no hay quien
 * lo saque, así que se mandan los 8 directamente; con el 0 adelante el relé lo
 * toma como comando desconocido, lo ignora y la transferencia igual sale bien.
 * Cada cambio se confirma leyendo el estado (byte 7, un bit por relé).
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

    /** Lee el estado sin cambiarlo. Devuelve null si el relé no está o no contesta. */
    fun powerPassing(): Boolean? = synchronized(lock) {
        val device = hub.relayDevice() ?: return null
        val link = connectionFor(device) ?: return null
        val status = readStatus(link) ?: return null
        !relayIsOn(status, relay = 1)
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
        if (feature(link, command(relay, enabled)) < 0) return false
        val status = readStatus(link) ?: return true
        return relayIsOn(status, relay) == enabled
    }

    private fun readStatus(link: UsbDeviceConnection): ByteArray? {
        val report = ByteArray(REPORT_SIZE)
        return if (fetch(link, report) >= REPORT_SIZE) report else null
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
        private const val REPORT_SIZE = 8

        /** ON (0xFF) o OFF (0xFD) para un relé, tal como llega al dispositivo. */
        fun command(relay: Int, enabled: Boolean): ByteArray {
            val report = ByteArray(REPORT_SIZE)
            report[0] = if (enabled) 0xFF.toByte() else 0xFD.toByte()
            report[1] = relay.toByte()
            return report
        }

        /** Byte 7 del estado: bit 0 el relé 1, bit 1 el relé 2. */
        fun relayIsOn(status: ByteArray, relay: Int): Boolean =
            (status[7].toInt() shr (relay - 1)) and 1 == 1
    }
}
