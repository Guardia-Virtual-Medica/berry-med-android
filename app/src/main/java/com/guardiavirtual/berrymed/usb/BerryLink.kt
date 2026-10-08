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
 * Si el puerto de ST abre y no aparecen cabeceras 55 AA, o si después se queda
 * muda 20 s, corta la alimentación con el relé. Si el cable desaparece, no lo
 * toca. Un puerto de otro fabricante que no contesta no es la plaqueta: queda
 * para el termómetro y no dispara el relé.
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
    private var appliedStartup: List<Pair<Int, Int>> = emptyList()
    private var lastPowerCycleAt: Long = 0L
    private var lastPowerCheckAt: Long = 0L
    private var powerCheckLogged = false
    private val openFailures = HashMap<Int, Int>()

    fun lastDataAgeSeconds(): Double? {
        val stamp = lastDataAtNano
        if (stamp == 0L) return null
        return (System.nanoTime() - stamp) / 1_000_000_000.0
    }

    fun run() {
        while (running) {
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
        if (hub.stPresent()) {
            powerCheckLogged = false
        } else {
            ensurePowerOn()
        }
        val drivers = hub.berryCandidates()
        openFailures.keys.retainAll(drivers.map { it.device.deviceId }.toSet())
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
            return openFailed(driver)
        }
        openFailures.remove(id)
        return try {
            val early = readUntil(handle, 400) { bytes -> hasHeader(bytes) || isThermometer(bytes) }
            if (isThermometer(early)) {
                hub.markNotBerry(id)
                release(handle)
                return Probe.Skip
            }
            val tune = tuning()
            val startup = Pm6750Protocol.buildStartupCommands(tune, log)
            handle.write(Pm6750Protocol.startupBytes(tune))
            val sawHeader = hasHeader(early) || hasHeader(readUntil(handle, 5_000) { hasHeader(it) })
            if (!sawHeader) {
                release(handle)
                if (muteMeansHungBerry(driver.device.vendorId)) {
                    log("[USB] ${driver.device.deviceName} abierto sin cabecera 55 AA")
                    return Probe.Mute
                }
                hub.markNotBerry(id)
                log(
                    "[USB] ${driver.device.deviceName} no es la plaqueta " +
                        "(%04X:%04X); queda para el termómetro".format(driver.device.vendorId, driver.device.productId),
                )
                return Probe.Skip
            }
            appliedStartup = startup
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
                applyTuningIfChanged()
            }
            return End.Stopped
        } finally {
            connected = false
            if (current === handle) current = null
        }
    }

    /**
     * Un puerto de ST que sigue enumerado pero no abre, varias veces seguidas,
     * es la plaqueta trabada sin contestar ni la configuración del puerto.
     * Uno de otro fabricante, o un fallo suelto, no corta nada.
     */
    private fun openFailed(driver: UsbSerialDriver): Probe {
        val device = driver.device
        if (!muteMeansHungBerry(device.vendorId)) return Probe.Skip
        val failures = (openFailures[device.deviceId] ?: 0) + 1
        openFailures[device.deviceId] = failures
        log("[USB] ${device.deviceName} no abre ($failures)")
        if (failures < OPEN_FAILURES_BEFORE_CUT || !hub.isPresent(device)) return Probe.Skip
        return Probe.Mute
    }

    /**
     * Si un corte anterior no repuso los 12 V (falló el relé, o la app se
     * cerró en medio del corte), la plaqueta no vuelve a aparecer y nada
     * más lo arreglaría. Una vez por minuto, mientras no esté, se le pide al
     * relé que deje pasar la alimentación. Si ya pasaba, no cambia nada.
     */
    private fun ensurePowerOn() {
        val now = System.nanoTime()
        if (lastPowerCheckAt != 0L && now - lastPowerCheckAt < RELAY_COOLDOWN_NANOS) return
        lastPowerCheckAt = now
        if (!relay.ensurePowerOn() || powerCheckLogged) return
        log("[WATCHDOG] No aparece la plaqueta: el relé deja pasar los 12 V")
        powerCheckLogged = true
    }

    private fun powerCycle(reason: String) {
        if (!relay.plugged()) {
            log("[WATCHDOG] $reason. No hay relé: no se corta la alimentación.")
            return
        }
        val now = System.nanoTime()
        if (!relayCooldownOver(lastPowerCycleAt, now)) {
            val seconds = (now - lastPowerCycleAt) / 1_000_000_000L
            log("[WATCHDOG] $reason. El último corte fue hace $seconds s: se espera para no cortarla mientras arranca.")
            return
        }
        log("[WATCHDOG] $reason. Corte de 12 V durante 5 s.")
        lastPowerCycleAt = now
        when (relay.restart()) {
            UsbRelay.Restart.Restored -> Unit
            UsbRelay.Restart.NotCut -> log("[WATCHDOG] El relé no respondió. No se hizo el corte.")
            UsbRelay.Restart.PowerNotRestored ->
                log("[WATCHDOG] Se cortó pero el relé no devolvió los 12 V. Se reintenta mientras no aparezca la plaqueta.")
        }
    }

    /** La ganancia y el modo se aplican apenas se guardan, sin esperar a reconectar. */
    private fun applyTuningIfChanged() {
        val tune = tuning()
        val wanted = Pm6750Protocol.buildStartupCommands(tune)
        if (wanted == appliedStartup) return
        if (!write(Pm6750Protocol.startupBytes(tune, log))) return
        appliedStartup = wanted
        log("[USB] Ganancia y modo de ECG actualizados")
    }

    private fun checkNibpTimeout() {
        if (!nibpRunning) return
        if (System.nanoTime() - nibpStartedAt > 90L * 1_000_000_000L) {
            log("[NIBP] Timeout sin cierre")
            stopNibp()
        }
    }

    private fun write(bytes: ByteArray): Boolean {
        synchronized(writeLock) {
            val port = current?.port ?: return false
            return try {
                port.write(bytes, 1_000)
                true
            } catch (error: Exception) {
                log("[USB] No se pudo escribir: $error")
                false
            }
        }
    }

    private fun release(handle: SerialHandle) {
        // Primero se baja `connected`: con la plaqueta arriba el termómetro
        // puede tomar cualquier puerto libre, y éste se libera recién abajo.
        if (current === handle) {
            current = null
            connected = false
        }
        try {
            handle.close()
        } catch (_: Exception) {
        }
        hub.unmark(handle.device.deviceId)
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

        /** Entre un corte y el siguiente, para que el PM6750 tenga tiempo de arrancar. */
        const val RELAY_COOLDOWN_NANOS = 60L * 1_000_000_000L

        /** Fallos seguidos al abrir un puerto de ST antes de tratarlo como plaqueta trabada. */
        const val OPEN_FAILURES_BEFORE_CUT = 3

        fun relayCooldownOver(lastCutAt: Long, now: Long): Boolean =
            lastCutAt == 0L || now - lastCutAt >= RELAY_COOLDOWN_NANOS

        /** Sólo un puerto de ST mudo es la plaqueta trabada. Otro mudo es el termómetro esperando. */
        fun muteMeansHungBerry(vendorId: Int): Boolean = vendorId == UsbHub.STM32_VENDOR

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
