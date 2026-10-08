// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.guardiavirtual.berrymed.protocol.DeviceTuning
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol
import com.hoho.android.usbserial.driver.UsbSerialDriver
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Abre la plaqueta, manda la secuencia de arranque y lee tramas.
 * Si el puerto abre y no aparecen cabeceras 55 AA, o si después se queda muda
 * 20 s, corta la alimentación con el relé. Si el cable desaparece, no lo toca.
 */
class BerryLink(
    private val hub: UsbHub,
    private val relay: UsbRelay,
    private val parser: BmDataParser,
    private val tuning: () -> DeviceTuning,
    private val log: (String) -> Unit,
) {
    @Volatile var running: Boolean = true
    @Volatile var connected: Boolean = false
    @Volatile var lastDataAtNano: Long = 0L

    @Volatile private var current: SerialHandle? = null
    private val writeLock = Any()
    @Volatile var nibpRunning: Boolean = false
    private var nibpStartedAt: Long = 0L

    fun lastDataAgeSeconds(): Double? {
        val stamp = lastDataAtNano
        if (stamp == 0L) return null
        return (System.nanoTime() - stamp) / 1_000_000_000.0
    }

    fun run() {
        while (running) {
            if (!hub.ensurePermissions()) {
                sleep(1_000)
                continue
            }
            when (val found = acquire()) {
                is Acquire.Ready -> {
                    try {
                        when (readLoop(found.handle)) {
                            End.UsbLost -> sleep(2_000)
                            End.Silence, End.Stopped -> Unit
                        }
                    } finally {
                        release(found.handle)
                    }
                }
                Acquire.None -> sleep(5_000)
            }
        }
    }

    fun stopNibp() {
        nibpRunning = false
        write(Pm6750Protocol.nibpStop())
    }

    fun startNibp() {
        synchronized(writeLock) {
            val port = current?.port
            if (port == null || nibpRunning) {
                log("[USB] NIBP no disponible")
                return
            }
            try {
                for ((a1, a2) in Pm6750Protocol.buildNibpCommands(tuning(), log)) {
                    port.write(Pm6750Protocol.buildCommand(a1, a2), 1_000)
                }
                nibpRunning = true
                nibpStartedAt = System.nanoTime()
                log("[USB] Medición de presión iniciada")
            } catch (error: Exception) {
                log("[USB] No se pudo iniciar la presión: $error")
            }
        }
    }

    fun onNibpStatus(status: Int) {
        if (!nibpRunning || !Pm6750Protocol.nibpIsTerminal(status)) return
        log("[USB] Presión terminada, status=0x%02X".format(status))
        stopNibp()
    }

    private fun acquire(): Acquire {
        val drivers = hub.serialDrivers()
        if (drivers.isEmpty()) {
            connected = false
            return Acquire.None
        }
        var mute = false
        for (driver in drivers) {
            when (val probe = probe(driver)) {
                is Probe.Berry -> return Acquire.Ready(probe.handle)
                Probe.Mute -> mute = true
                Probe.Skip -> Unit
            }
        }
        connected = false
        if (mute) powerCycle("el puerto abre pero el PM6750 no transmite")
        return Acquire.None
    }

    private fun probe(driver: UsbSerialDriver): Probe {
        val id = driver.device.deviceId
        if (!hub.mark(id)) return Probe.Skip
        val handle = hub.open(driver)
        if (handle == null) {
            hub.unmark(id)
            return Probe.Skip
        }
        return try {
            val early = readUntil(handle, 400) { bytes -> hasHeader(bytes) || isThermometer(bytes) }
            if (isThermometer(early)) {
                release(handle)
                return Probe.Skip
            }
            handle.write(Pm6750Protocol.startupBytes(tuning(), log))
            val sawHeader = hasHeader(early) || hasHeader(readUntil(handle, 5_000) { hasHeader(it) })
            if (!sawHeader) {
                log("[USB] ${driver.device.deviceName} abierto sin cabecera 55 AA")
                release(handle)
                return Probe.Mute
            }
            parser.resetData()
            connected = true
            log("[USB] Plaqueta en ${driver.device.deviceName}")
            Probe.Berry(handle)
        } catch (error: Exception) {
            log("[USB] Error al preparar ${driver.device.deviceName}: $error")
            release(handle)
            Probe.Skip
        }
    }

    private fun readLoop(handle: SerialHandle): End {
        current = handle
        val buffer = ByteArray(256)
        var silentSince = System.nanoTime()
        var warned = false
        lastDataAtNano = silentSince
        try {
            while (running) {
                val count = try {
                    handle.read(buffer, 300)
                } catch (error: IOException) {
                    if (!hub.isPresent(handle.device)) {
                        log("[USB] Se perdió el cable de la plaqueta")
                        parser.resetData()
                        return End.UsbLost
                    }
                    log("[USB] Lectura interrumpida: $error")
                    0
                }
                if (count <= 0) {
                    if (!hub.isPresent(handle.device)) {
                        log("[USB] Se perdió el cable de la plaqueta")
                        parser.resetData()
                        return End.UsbLost
                    }
                    val silentSeconds = (System.nanoTime() - silentSince) / 1_000_000_000.0
                    if (silentSeconds > 5 && !warned) {
                        log("[USB] Sin datos hace ${silentSeconds.toInt()} s")
                        warned = true
                    }
                    if (silentSeconds >= SILENCE_SECONDS) {
                        log("[WATCHDOG] PM6750 sin datos durante ${SILENCE_SECONDS.toInt()} s")
                        release(handle)
                        powerCycle("el PM6750 se quedó mudo")
                        return End.Silence
                    }
                    checkNibpTimeout()
                    continue
                }
                silentSince = System.nanoTime()
                lastDataAtNano = silentSince
                if (warned) {
                    log("[USB] Volvieron los datos")
                    warned = false
                }
                parser.addData(buffer.copyOf(count))
                checkNibpTimeout()
            }
            return End.Stopped
        } finally {
            connected = false
            if (current === handle) current = null
        }
    }

    private fun powerCycle(reason: String) {
        if (!relay.plugged()) {
            log("[WATCHDOG] $reason. No hay relé: no se corta la alimentación.")
            return
        }
        log("[WATCHDOG] $reason. Corte de 12 V durante 5 s.")
        if (!relay.restart()) log("[WATCHDOG] El relé no respondió. No se hizo el corte.")
    }

    private fun checkNibpTimeout() {
        if (!nibpRunning) return
        if (System.nanoTime() - nibpStartedAt > 90L * 1_000_000_000L) {
            log("[NIBP] Timeout sin cierre")
            stopNibp()
        }
    }

    private fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            val port = current?.port ?: return
            try {
                port.write(bytes, 1_000)
            } catch (error: Exception) {
                log("[USB] No se pudo escribir: $error")
            }
        }
    }

    private fun release(handle: SerialHandle) {
        try {
            handle.close()
        } catch (_: Exception) {
        }
        hub.unmark(handle.device.deviceId)
        if (current === handle) current = null
        connected = false
    }

    private sealed class Acquire {
        data class Ready(val handle: SerialHandle) : Acquire()
        data object None : Acquire()
    }

    private sealed class Probe {
        data class Berry(val handle: SerialHandle) : Probe()
        data object Mute : Probe()
        data object Skip : Probe()
    }

    private enum class End { UsbLost, Silence, Stopped }

    companion object {
        const val SILENCE_SECONDS = 20.0

        fun hasHeader(bytes: ByteArray): Boolean {
            for (i in 0 until bytes.size - 1) {
                if (bytes[i] == 0x55.toByte() && bytes[i + 1] == 0xAA.toByte()) return true
            }
            return false
        }

        fun isThermometer(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            return String(bytes, Charsets.UTF_8).contains("valor_temperatura_ir")
        }

        fun readUntil(handle: SerialHandle, budgetMs: Int, done: (ByteArray) -> Boolean): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            val deadline = System.nanoTime() + budgetMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val count = handle.read(buffer, 200)
                if (count > 0) {
                    out.write(buffer, 0, count)
                    val snapshot = out.toByteArray()
                    if (done(snapshot)) return snapshot
                }
            }
            return out.toByteArray()
        }

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun sleep(ms: Long) = Companion.sleep(ms)
}
