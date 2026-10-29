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
 * muda 20 s, cierra el puerto y vuelve a empezar: abre de nuevo y repite la
 * secuencia de arranque. La plaqueta ya no necesita que le corten la
 * alimentación: con el firmware corregido se recupera sola. Un puerto de otro
 * fabricante que no contesta no es la plaqueta: queda para el termómetro.
 */
class BerryLink(
    private val hub: UsbHub,
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
    @Volatile private var nibpStartedAt: Long = 0L
    @Volatile private var lastNibpFrameAt: Long = 0L
    private var appliedStartup: List<Pair<Int, Int>> = emptyList()

    fun lastDataAgeSeconds(): Double? {
        val stamp = lastDataAtNano
        if (stamp == 0L) return null
        return (System.nanoTime() - stamp) / 1_000_000_000.0
    }

    fun run() {
        while (running) {
            // Una excepción que escape mata este hilo y, en Android, la app entera.
            try {
                connectAndRead()
            } catch (error: Exception) {
                log("[USB] Error inesperado: $error")
                sleep(2_000)
            }
        }
    }

    private fun connectAndRead() {
        when (val found = acquire()) {
            is Acquire.Ready -> {
                try {
                    when (readLoop(found.handle)) {
                        End.UsbLost -> sleep(2_000)
                        // Un momento antes de reabrir, para que el puerto se cierre del todo.
                        End.Silence -> sleep(1_000)
                        End.Stopped -> Unit
                    }
                } finally {
                    release(found.handle)
                }
            }
            Acquire.None -> sleep(5_000)
        }
    }

    fun portName(): String? = current?.device?.deviceName

    fun stopNibp() {
        nibpRunning = false
        write(Pm6750Protocol.nibpStop())
    }

    /** Devuelve true si la medición arrancó. */
    fun startNibp(): Boolean {
        synchronized(writeLock) {
            val port = current?.port
            if (port == null || nibpRunning) {
                log("[USB] NIBP no disponible")
                return false
            }
            return try {
                for ((a1, a2) in Pm6750Protocol.buildNibpCommands(tuning(), log)) {
                    port.write(Pm6750Protocol.buildCommand(a1, a2), 1_000)
                }
                // Las marcas antes que la bandera: el hilo de lectura mira la bandera y después las marcas.
                nibpStartedAt = System.nanoTime()
                lastNibpFrameAt = 0L
                nibpRunning = true
                log("[USB] Medición de presión iniciada")
                true
            } catch (error: Exception) {
                log("[USB] No se pudo iniciar la presión: $error")
                false
            }
        }
    }

    fun onNibpStatus(status: Int) {
        if (!nibpRunning) return
        lastNibpFrameAt = System.nanoTime()
        if (!Pm6750Protocol.nibpIsTerminal(status)) return
        log("[USB] Presión terminada, status=0x%02X".format(status))
        stopNibp()
    }

    private fun acquire(): Acquire {
        val drivers = hub.berryCandidates()
        if (drivers.isEmpty()) {
            connected = false
            return Acquire.None
        }
        for (driver in drivers) {
            when (val probe = probe(driver)) {
                is Probe.Berry -> return Acquire.Ready(probe.handle)
                Probe.Skip -> Unit
            }
        }
        connected = false
        return Acquire.None
    }

    private fun probe(driver: UsbSerialDriver): Probe {
        val id = driver.device.deviceId
        if (!hub.mark(id)) return Probe.Skip
        val handle = hub.open(driver)
        if (handle == null) {
            hub.unmark(id)
            // Un puerto de ST que no abre sigue siendo la plaqueta: se reintenta en la próxima vuelta.
            if (isStPort(driver.device.vendorId)) log("[USB] ${driver.device.deviceName} no abre; se reintenta")
            return Probe.Skip
        }
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
                if (isStPort(driver.device.vendorId)) {
                    log("[USB] ${driver.device.deviceName} abierto sin cabecera 55 AA; se reintenta")
                    return Probe.Skip
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
                val readStartedAt = System.nanoTime()
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
                    if (valuesAreStale(silentSeconds) && !warned) {
                        // Con la plaqueta muda, sus últimos valores no se mandan como si fueran
                        // actuales: se borran, igual que al desenchufarla. El termómetro sigue.
                        log("[USB] Sin datos hace ${silentSeconds.toInt()} s: se borran los valores de la plaqueta")
                        parser.resetData()
                        warned = true
                    }
                    if (silentSeconds >= SILENCE_SECONDS) {
                        log("[WATCHDOG] PM6750 sin datos durante ${SILENCE_SECONDS.toInt()} s: se cierra el puerto y se vuelve a abrir")
                        return End.Silence
                    }
                    checkNibpTimeout()
                    pauseIfReadReturnedAtOnce(readStartedAt)
                    continue
                }
                silentSince = System.nanoTime()
                lastDataAtNano = silentSince
                if (warned) {
                    log("[USB] Volvieron los datos")
                    warned = false
                }
                try {
                    parser.addData(buffer.copyOf(count))
                } catch (error: Exception) {
                    // Se pierden los bytes pendientes, no la presión ni los signos ya medidos.
                    log("[USB] Trama descartada: $error")
                    parser.dropPending()
                }
                checkNibpTimeout()
                applyTuningIfChanged()
            }
            return End.Stopped
        } finally {
            connected = false
            if (current === handle) current = null
        }
    }

    /** Un endpoint trabado puede devolver 0 sin esperar: sin esto el hilo gira sin pausa. */
    private fun pauseIfReadReturnedAtOnce(startedAt: Long) {
        if (System.nanoTime() - startedAt < EMPTY_READ_PAUSE_MS * 1_000_000L) sleep(EMPTY_READ_PAUSE_MS)
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

    /**
     * El módulo manda el estado de la presión dos veces por segundo mientras
     * mide y se calla al terminar. Un final que no figura entre los terminales
     * (error, interferencia, sin resultado) se nota porque deja de llegar.
     * Antes del primer estado no se mide el silencio: el equipo a veces tarda
     * en empezar a inflar, y ahí sólo corre el tope de [NIBP_TIMEOUT_NANOS].
     */
    private fun checkNibpTimeout() {
        if (!nibpRunning) return
        val now = System.nanoTime()
        if (now - nibpStartedAt > NIBP_TIMEOUT_NANOS) {
            log("[NIBP] Timeout sin cierre")
            stopNibp()
            return
        }
        if (nibpWentQuiet(lastNibpFrameAt, now)) {
            log("[NIBP] El equipo dejó de informar la presión; se da por terminada")
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
        data object Skip : Probe()
    }

    private enum class End { UsbLost, Silence, Stopped }

    companion object {
        const val SILENCE_SECONDS = 20.0

        /** Sin tramas este tiempo, los valores de la plaqueta ya no son actuales (manda 250 por segundo). */
        const val STALE_SECONDS = 5.0

        fun valuesAreStale(silentSeconds: Double): Boolean = silentSeconds >= STALE_SECONDS

        /**
         * Una medición puede tardar bastante más de 40 s, contando lo que
         * demora en empezar a inflar y un reinflado. Recién a los 3 minutos
         * se da por colgada y se desinfla el manguito.
         */
        const val NIBP_TIMEOUT_NANOS = 180L * 1_000_000_000L

        const val EMPTY_READ_PAUSE_MS = 50L

        /** Ya informando, sin estado durante este tiempo la medición terminó (mientras mide llega dos veces por segundo). */
        const val NIBP_QUIET_NANOS = 30L * 1_000_000_000L

        /** `lastFrameAt == 0`: el equipo todavía no informó nada de esta medición. */
        fun nibpWentQuiet(lastFrameAt: Long, now: Long): Boolean =
            lastFrameAt != 0L && now - lastFrameAt > NIBP_QUIET_NANOS

        /** Sólo un puerto de ST mudo sigue siendo la plaqueta. Otro mudo es el termómetro esperando. */
        fun isStPort(vendorId: Int): Boolean = vendorId == UsbHub.STM32_VENDOR

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
