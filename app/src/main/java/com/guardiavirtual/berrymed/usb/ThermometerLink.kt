// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import com.guardiavirtual.berrymed.protocol.ThermoReading
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.protocol.applyThermometer
import com.guardiavirtual.berrymed.protocol.parseThermometerLine
import java.io.IOException

/**
 * Termómetro infrarrojo en otro puerto serie. Cada línea es un JSON con la lectura,
 * y sólo llega cuando alguien mide: el puerto se deja abierto esperando, como
 * hace el monitor de Windows. Qué puerto puede tomar lo decide [UsbHub.thermometerCandidates].
 */
class ThermometerLink(
    private val hub: UsbHub,
    private val berryUp: () -> Boolean,
    private val log: (String) -> Unit,
) {
    @Volatile var running: Boolean = true
    @Volatile var connected: Boolean = false
    @Volatile var measured: Boolean = false
    private var latest: ThermoReading? = null
    private val lock = Any()

    fun latest(): ThermoReading? = synchronized(lock) { latest }

    fun resetLatest() {
        synchronized(lock) { latest = null }
    }

    fun postedTemperature(berry: String): String {
        val reading = latest() ?: return berry
        val vitals = VitalSigns(temperature = berry)
        applyThermometer(vitals, reading)
        return vitals.temperature
    }

    fun run() {
        while (running) {
            // Una excepción que escape mata este hilo y, en Android, la app entera.
            try {
                connectAndRead()
            } catch (error: Exception) {
                log("[THERM] Error inesperado: $error")
                sleep(2_000)
            }
        }
    }

    private fun connectAndRead() {
        val handle = acquire()
        if (handle == null) {
            sleep(2_000)
            return
        }
        try {
            readLines(handle)
        } finally {
            connected = false
            measured = false
            try {
                handle.close()
            } catch (_: Exception) {
            }
            hub.unmark(handle.device.deviceId)
        }
        if (running) sleep(2_000)
    }

    private fun acquire(): SerialHandle? {
        for (driver in hub.thermometerCandidates(berryUp())) {
            val id = driver.device.deviceId
            if (!hub.mark(id)) continue
            val handle = hub.open(driver)
            if (handle == null) {
                hub.unmark(id)
                continue
            }
            connected = true
            log("[THERM] Esperando mediciones en ${driver.device.deviceName}")
            return handle
        }
        return null
    }

    private fun readLines(handle: SerialHandle) {
        val pending = StringBuilder()
        val buffer = ByteArray(256)
        while (running && hub.isPresent(handle.device)) {
            val readStartedAt = System.nanoTime()
            val count = try {
                handle.read(buffer, 500)
            } catch (_: IOException) {
                break
            }
            if (count <= 0) {
                // Un endpoint trabado puede devolver 0 sin esperar: sin esto el hilo gira sin pausa.
                if (System.nanoTime() - readStartedAt < BerryLink.EMPTY_READ_PAUSE_MS * 1_000_000L) {
                    sleep(BerryLink.EMPTY_READ_PAUSE_MS)
                }
                continue
            }
            val chunk = buffer.copyOf(count)
            if (BerryLink.hasHeader(chunk)) {
                // Tramas 55 AA: es una plaqueta. Se le devuelve a BerryLink.
                log("[THERM] ${handle.device.deviceName} manda tramas de la plaqueta; se libera")
                hub.clearNotBerry(handle.device.deviceId)
                break
            }
            pending.append(String(chunk, Charsets.UTF_8))
            ingest(pending)
            if (pending.length > 4_096) pending.delete(0, pending.length - 256)
        }
        log("[THERM] Lectura detenida")
    }

    private fun ingest(buffer: StringBuilder) {
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) return
            val line = buffer.substring(0, newline)
            buffer.delete(0, newline + 1)
            store(line)
        }
    }

    private fun store(line: String) {
        val reading = parseThermometerLine(line) ?: return
        synchronized(lock) { latest = reading }
        if (!measured) log("[THERM] Primera medición: ${reading.value} ${reading.unit}")
        measured = true
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
