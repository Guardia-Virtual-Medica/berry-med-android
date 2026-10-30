// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.Closeable

/**
 * Hub YKUSH3 de Yepkit: tres puertos USB que se encienden y apagan por HID.
 * En el tótem cada puerto lleva un equipo (otoscopio, adaptador del
 * estetoscopio, auricular), así que apagar un puerto es desenchufar ese equipo
 * para Android, y encenderlo es enchufarlo de nuevo.
 *
 * El protocolo es el de la hoja de datos (v1.2.1): paquetes de 64 bytes por la
 * interfaz HID. El primer byte es el comando y el puerto va en los bits bajos:
 * 0x1N enciende el puerto N, 0x0N lo apaga y 0x2N lo consulta; el comando se
 * repite en el segundo byte. La respuesta trae 0x01 en el primer byte si salió
 * bien y, al consultar, 0x1N (encendido) o 0x0N (apagado) en el segundo. 0x41
 * guarda en el hub cómo arranca un puerto: es persistente y la hoja no ofrece
 * leerlo, así que sale sólo con el botón de Testing.
 *
 * Esta clase no toca los puertos sola: cambia lo que le piden. Quienes le piden
 * son la pantalla, el Panel Local, la teleconsulta y `TotemRuntime`, que lleva
 * los puertos al estado de reposo cuando el hub aparece.
 */
class YkushHub(private val hub: UsbHub, private val log: (String) -> Unit) : Closeable {
    private var connection: UsbDeviceConnection? = null
    private var deviceId: Int? = null
    private var claimed: UsbInterface? = null
    private var input: UsbEndpoint? = null
    private var output: UsbEndpoint? = null
    private val lock = Any()

    /** Último estado leído de cada puerto, para la pantalla. null: nunca se leyó o el hub no contestó. */
    @Volatile var lastStates: List<Boolean?> = List(PORT_COUNT) { null }
        private set

    fun plugged(): Boolean = hub.ykushPlugged()

    /**
     * Enciende o apaga un puerto y después relee los tres. Devuelve false si el
     * hub no está, no tiene permiso o rechazó la orden. Bloquea hasta cuatro
     * segundos (la orden y la relectura): llamarlo fuera del hilo de la pantalla.
     */
    fun setPort(port: Int, on: Boolean): Boolean = synchronized(lock) {
        require(port in 1..PORT_COUNT) { "puerto $port" }
        val reply = exchange(packet(command(port, on)))
        if (reply == null) {
            log("[YKUSH] El hub no contesta: no se pudo ${if (on) "encender" else "apagar"} el puerto $port")
            return false
        }
        if (!ok(reply)) {
            log("[YKUSH] El hub rechazó ${if (on) "encender" else "apagar"} el puerto $port (0x%02X)".format(reply[0]))
            return false
        }
        log("[YKUSH] Puerto $port ${if (on) "encendido" else "apagado"}")
        readAll()
        true
    }

    /**
     * Lee el estado de los tres puertos sin cambiarlos, de a uno: 0x2A no está
     * en la hoja de datos y pykush sólo lo usa con firmware 2 o superior. Un
     * puerto que no contesta queda null y los demás se conservan. Bloquea hasta
     * tres segundos: llamarlo fuera del hilo de la pantalla.
     */
    fun readAll(): List<Boolean?> = synchronized(lock) {
        val states = (1..PORT_COUNT).map { port -> stateFrom(exchange(packet(statusCommand(port)))) }
        lastStates = states
        states
    }

    /**
     * Guarda en el hub cómo arranca [port] cuando recibe energía. Devuelve si
     * el hub aceptó. Queda en su memoria: nunca se manda solo, porque no se
     * puede leer antes y cada arranque del servicio la gastaría.
     */
    fun setDefaultState(port: Int, on: Boolean): Boolean = synchronized(lock) {
        require(port in 1..PORT_COUNT) { "puerto $port" }
        val reply = exchange(defaultStatePacket(port, on))
        val accepted = reply != null && ok(reply)
        log(
            if (accepted) {
                "[YKUSH] Puerto $port guardado para arrancar ${if (on) "encendido" else "apagado"}"
            } else {
                "[YKUSH] El hub no aceptó guardar el arranque del puerto $port"
            },
        )
        accepted
    }

    /** El identificador del hub enchufado y con permiso; null si todavía no se puede usar. */
    fun usableDeviceId(): Int? = hub.ykushDevice()?.deviceId

    private fun exchange(packet: ByteArray): ByteArray? {
        val device = hub.ykushDevice() ?: return null
        val link = connectionFor(device) ?: return null
        val sent = send(link, packet)
        if (sent < 0) {
            close()
            return null
        }
        val reply = ByteArray(PACKET_SIZE)
        val endpoint = input ?: return null
        val got = link.bulkTransfer(endpoint, reply, reply.size, TIMEOUT_MS)
        if (got < 2) {
            close()
            return null
        }
        return reply
    }

    /** Por el endpoint de salida si lo hay; si no, como SET_REPORT por el control. */
    private fun send(link: UsbDeviceConnection, packet: ByteArray): Int {
        val endpoint = output
        return if (endpoint != null) {
            link.bulkTransfer(endpoint, packet, packet.size, TIMEOUT_MS)
        } else {
            link.controlTransfer(0x21, 0x09, 0x0200, claimed?.id ?: 0, packet, packet.size, TIMEOUT_MS)
        }
    }

    private fun connectionFor(device: UsbDevice): UsbDeviceConnection? {
        if (deviceId == device.deviceId && connection != null) return connection
        close()
        val hid = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID } ?: return null
        val opened = hub.manager.openDevice(device) ?: return null
        if (!opened.claimInterface(hid, true)) {
            opened.close()
            return null
        }
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until hid.endpointCount) {
            val endpoint = hid.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) inEp = endpoint else outEp = endpoint
        }
        if (inEp == null) {
            opened.releaseInterface(hid)
            opened.close()
            return null
        }
        connection = opened
        deviceId = device.deviceId
        claimed = hid
        input = inEp
        output = outEp
        return opened
    }

    override fun close() {
        try {
            val intf = claimed
            if (intf != null) connection?.releaseInterface(intf)
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        deviceId = null
        claimed = null
        input = null
        output = null
    }

    companion object {
        const val PORT_COUNT = 3
        const val PACKET_SIZE = 64
        private const val TIMEOUT_MS = 1_000
        const val OK: Byte = 0x01
        private const val SET_DEFAULT: Byte = 0x41

        /** 0x1N enciende el puerto N, 0x0N lo apaga. */
        fun command(port: Int, on: Boolean): Byte = ((if (on) 0x10 else 0x00) or port).toByte()

        /** 0x2N consulta el puerto N. */
        fun statusCommand(port: Int): Byte = (0x20 or port).toByte()

        /** El comando en el primer byte y repetido en el segundo, como piden las tablas 2, 4 y 6 de la hoja. */
        fun packet(cmd: Byte): ByteArray = ByteArray(PACKET_SIZE).also {
            it[0] = cmd
            it[1] = cmd
        }

        /** 0x41, el puerto y 0x01 para arrancar encendido o 0x00 apagado (sección 6.6 de la hoja). */
        fun defaultStatePacket(port: Int, on: Boolean): ByteArray = ByteArray(PACKET_SIZE).also {
            it[0] = SET_DEFAULT
            it[1] = port.toByte()
            it[2] = if (on) 0x01 else 0x00
        }

        fun ok(reply: ByteArray): Boolean = reply.isNotEmpty() && reply[0] == OK

        /** La respuesta a 0x2N: 0x1N encendido, 0x0N apagado. Sin respuesta, rechazada o rara, null. */
        fun stateFrom(reply: ByteArray?): Boolean? {
            if (reply == null || reply.size < 2 || !ok(reply)) return null
            return when (reply[1].toInt() and 0xF0) {
                0x10 -> true
                0x00 -> false
                else -> null
            }
        }
    }
}
