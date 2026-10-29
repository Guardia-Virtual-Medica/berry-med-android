// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.protocol.ParserStats
import com.guardiavirtual.berrymed.tele.TeleView
import com.guardiavirtual.berrymed.usb.DeviceInventory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La lógica del panel Testing, sin Android: tasas por segundo de cada tipo de
 * trama y la prueba de 5 s de cada medición de la plaqueta. No lee el USB ni
 * envía nada: mira los contadores del parser, que no se consumen.
 */
class TestingSession(private val clock: () -> Long = System::nanoTime) {
    enum class Measure(val label: String) {
        Ecg("ECG"),
        Spo2("SpO₂"),
        Resp("Respiración"),
        Temp("Temperatura"),
    }

    /** `ok == null`: la prueba sigue corriendo. */
    data class Verdict(val ok: Boolean?, val message: String)

    data class Rates(
        val ecg: Int = 0,
        val peaks: Int = 0,
        val spo2Wave: Int = 0,
        val respWave: Int = 0,
        val temperature: Int = 0,
        val badChecksum: Int = 0,
    )

    private val lock = Any()
    private val running = HashMap<Measure, Pair<ParserStats, Long>>()
    private val verdicts = HashMap<Measure, Verdict>()
    private var rateFrom: Pair<ParserStats, Long>? = null
    private var rates = Rates()

    fun check(measure: Measure, stats: ParserStats) = synchronized(lock) {
        running[measure] = stats to clock()
        verdicts[measure] = Verdict(null, "Probando…")
    }

    /** Cierra las pruebas que cumplieron los 5 s y recalcula las tasas una vez por segundo. */
    fun poll(stats: ParserStats, berryUp: Boolean): Map<Measure, Verdict> = synchronized(lock) {
        val now = clock()
        val from = rateFrom
        if (from == null) {
            rateFrom = stats to now
        } else if (now - from.second >= RATE_NANOS) {
            rates = rates(from.first, stats, (now - from.second) / 1_000_000_000.0)
            rateFrom = stats to now
        }
        val done = ArrayList<Measure>()
        for ((measure, start) in running) {
            val elapsed = now - start.second
            verdicts[measure] = when {
                !berryUp -> Verdict(false, "Plaqueta sin conectar")
                elapsed < CHECK_NANOS -> Verdict(null, "Probando… ${(CHECK_NANOS - elapsed) / 1_000_000_000L + 1} s")
                else -> verdict(measure, start.first, stats, elapsed / 1_000_000_000.0)
            }
            if (verdicts.getValue(measure).ok != null) done += measure
        }
        done.forEach(running::remove)
        HashMap(verdicts)
    }

    fun rates(): Rates = synchronized(lock) { rates }

    fun reset() = synchronized(lock) {
        running.clear()
        verdicts.clear()
        rateFrom = null
        rates = Rates()
    }

    companion object {
        const val CHECK_NANOS = 5L * 1_000_000_000L
        private const val RATE_NANOS = 1_000_000_000L

        /** El PM6750 manda 250 muestras de ECG por segundo; con menos de 200 algo se pierde. */
        const val MIN_ECG_PER_SECOND = 200

        fun rates(from: ParserStats, to: ParserStats, seconds: Double): Rates {
            if (seconds <= 0.0) return Rates()
            val a = from.frames
            val b = to.frames
            fun per(delta: Long) = Math.round(delta / seconds).toInt()
            return Rates(
                ecg = per(b.ecg - a.ecg),
                peaks = per(b.peaks - a.peaks),
                spo2Wave = per(b.spo2Wave - a.spo2Wave),
                respWave = per(b.respWave - a.respWave),
                temperature = per(b.temperature - a.temperature),
                badChecksum = per(b.badChecksum - a.badChecksum),
            )
        }

        /**
         * Los umbrales de las ondas de SpO₂ y respiración no están confirmados
         * con la plaqueta: por ahora alcanza con que la onda llegue.
         */
        fun verdict(measure: Measure, from: ParserStats, to: ParserStats, seconds: Double): Verdict {
            val a = from.frames
            val b = to.frames
            val vitals = to.vitals
            return when (measure) {
                Measure.Ecg -> {
                    val perSecond = Math.round((b.ecg - a.ecg) / seconds).toInt()
                    when {
                        perSecond == 0 -> Verdict(false, "No llega la onda de ECG")
                        perSecond < MIN_ECG_PER_SECOND -> Verdict(false, "Llegan $perSecond muestras/s, se esperan unas 250")
                        to.ecgInfo.leadOff == true -> Verdict(false, "Electrodos sueltos")
                        to.ecgInfo.weakSignal == true -> Verdict(true, "OK, $perSecond muestras/s, señal débil")
                        else -> Verdict(true, "OK, $perSecond muestras/s, FC ${vitals.heartRate}")
                    }
                }
                Measure.Spo2 -> when {
                    b.spo2Wave == a.spo2Wave -> Verdict(false, "No llega la onda de SpO₂")
                    b.spo2 == a.spo2 -> Verdict(false, "No llegan los valores de SpO₂")
                    !vitals.spo2Pulse.first().isDigit() -> Verdict(false, "Sin dedo en el sensor")
                    else -> Verdict(true, "OK, SpO₂/pulso ${vitals.spo2Pulse}")
                }
                Measure.Resp -> when {
                    b.respWave == a.respWave -> Verdict(false, "No llega la onda de respiración")
                    else -> Verdict(true, "OK, FR ${vitals.respRate}")
                }
                Measure.Temp -> when {
                    b.temperature == a.temperature -> Verdict(false, "La plaqueta no manda temperatura")
                    !vitals.temperature.first().isDigit() -> Verdict(false, "Sensor de temperatura sin conectar")
                    else -> Verdict(true, "OK, ${vitals.temperature} °C")
                }
            }
        }
    }
}

/** Los últimos logs, para la consola de Testing. Logcat sigue recibiendo todo. */
class ConsoleLog(private val capacity: Int = 200) {
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) = synchronized(lines) {
        lines.addLast("[${format.format(Date())}] $message")
        while (lines.size > capacity) lines.removeFirst()
    }

    /** Lo más nuevo arriba. */
    fun snapshot(): List<String> = synchronized(lines) { lines.reversed() }

    fun clear() = synchronized(lines) { lines.clear() }
}

/** Un puerto del YKUSH3 en la pantalla: qué equipo lleva y si está encendido (null: no se leyó). */
data class YkushPortView(val port: Int, val role: String, val on: Boolean?)

/** Lo que muestra el panel Testing en cada refresco. */
data class TestingSnapshot(
    val testing: Boolean,
    val sessionActive: Boolean,
    val berryUp: Boolean,
    val berryPort: String?,
    val lastDataAge: Double?,
    val rates: TestingSession.Rates,
    val verdicts: Map<TestingSession.Measure, TestingSession.Verdict>,
    val stats: ParserStats,
    val nibpRunning: Boolean,
    val thermoConnected: Boolean,
    val thermoPort: String?,
    val thermoReading: String?,
    val thermoAge: Long?,
    val thermoLine: String,
    val ykushPlugged: Boolean,
    val ykushPorts: List<YkushPortView>,
    val inventory: DeviceInventory.Snapshot,
    val url: String?,
    val tele: TeleView,
    val preview: String?,
    val console: List<String>,
)
