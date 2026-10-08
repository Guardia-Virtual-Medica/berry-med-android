// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import com.guardiavirtual.berrymed.protocol.ThermoReading
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.protocol.applyThermometer
import com.guardiavirtual.berrymed.protocol.parseThermometerLine
import java.io.IOException

/** Termómetro infrarrojo en otro puerto serie. Cada línea es un JSON con la lectura. */
class ThermometerLink(
    private val hub: UsbHub,
    private val log: (String) -> Unit,
) {
    @Volatile var running: Boolean = true
    @Volatile var connected: Boolean = false
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
            if (!hub.ensurePermissions()) {
                sleep(1_000)
                continue
            }
            val handle = acquire()
            if (handle == null) {
                sleep(2_000)
                continue
            }
            try {
                readLines(handle)
            } finally {
                connected = false
                try {
                    handle.close()
                } catch (_: Exception) {
                }
                hub.unmark(handle.device.deviceId)
            }
            if (running) sleep(2_000)
        }
    }

    private fun acquire(): SerialHandle? {
        for (driver in hub.serialDrivers()) {
            val id = driver.device.deviceId
            if (!hub.mark(id)) continue
            val handle = hub.open(driver)
            if (handle == null) {
                hub.unmark(id)
                continue
            }
            try {
                val sample = BerryLink.readUntil(handle, 800) { BerryLink.isThermometer(it) }
                if (!BerryLink.isThermometer(sample)) {
                    handle.close()
                    hub.unmark(id)
                    continue
                }
                ingest(String(sample, Charsets.UTF_8))
                connected = true
                log("[THERM] Conectado en ${driver.device.deviceName}")
                return handle
            } catch (error: Exception) {
                log("[THERM] No se pudo abrir ${driver.device.deviceName}: $error")
                try {
                    handle.close()
                } catch (_: Exception) {
                }
                hub.unmark(id)
            }
        }
        return null
    }

    private fun readLines(handle: SerialHandle) {
        val pending = StringBuilder()
        val buffer = ByteArray(256)
        while (running && hub.isPresent(handle.device)) {
            val count = try {
                handle.read(buffer, 500)
            } catch (_: IOException) {
                break
            }
            if (count <= 0) continue
            pending.append(String(buffer, 0, count, Charsets.UTF_8))
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

    private fun ingest(text: String) {
        text.lineSequence().forEach(::store)
    }

    private fun store(line: String) {
        val reading = parseThermometerLine(line) ?: return
        synchronized(lock) { latest = reading }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
