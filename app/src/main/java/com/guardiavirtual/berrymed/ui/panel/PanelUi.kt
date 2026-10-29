// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol
import com.guardiavirtual.berrymed.service.LocalSnapshot
import java.time.ZoneId

/** El dispositivo abierto en el área principal, en lugar del ECG. */
enum class PanelDevice { OTOSCOPE, STETHOSCOPE, CAMERA }

/** El estado de un chip de la cabecera. */
enum class Link { CONNECTED, CONNECTING, DISCONNECTED }

/**
 * El color de un texto de estado. Color es estado: ok es conectado o en
 * vivo, warn es conectando o señal débil, y faint es desconectado.
 */
enum class Tone { OK, WARN, MUTED, FAINT }

data class Status(val text: String, val tone: Tone)

data class Chips(
    val ecg: Link,
    val spo2: Link,
    val pressure: Link,
    val temperature: Link,
    val otoscope: Link,
    val stethoscope: Link,
)

/** Las derivaciones a la vista: la II sola, las tres de los miembros o las siete. */
enum class EcgLeads(val label: String, val leads: List<String>) {
    II("II", listOf("II")),
    THREE("3", listOf("I", "II", "III")),
    SEVEN("7", Pm6750Protocol.LEADS),
}

/** Lo que muestra la tarjeta de la presión. */
sealed interface PressureCard {
    /** Nunca se midió en esta sesión. */
    data object Empty : PressureCard

    /** "120/80", la media "(93)" y "10:07 · hace 4 min" (vacío si no se sabe la hora). */
    data class Result(val value: String, val mean: String, val at: String) : PressureCard

    /** La presión del manguito en vivo, la fase y la barra, de 0 a 1. */
    data class Measuring(val cuff: String, val phase: String, val progress: Float) : PressureCard

    /** Terminó sin presión: manguito flojo, demoró o se canceló. */
    data object NoResult : PressureCard
}

/** El termómetro infrarrojo manda una lectura cuando alguien aprieta el gatillo: no hay botones. */
sealed interface TempCard {
    data object Disconnected : TempCard

    /** Conectado y sin lectura todavía. */
    data object Waiting : TempCard

    /** "36,6" y "IR · 10:12". */
    data class Reading(val value: String, val at: String) : TempCard
}

/** Un tile del dock: si se puede abrir y qué dice debajo del nombre. */
data class Tile(val enabled: Boolean, val status: Status)

/** La cámara de la tablet, que la maneja la pantalla y no el runtime. */
enum class CameraState { STARTING, LIVE, PAUSED, UNAVAILABLE, MISSING }

/** Lo que sabe la pantalla y no el runtime. */
data class ScreenState(
    /** La imagen del otoscopio abierta, con su camino ("por UVC"), o null si no hay. */
    val otoscopeLabel: String? = null,
    val camera: CameraState = CameraState.STARTING,
    val cameraFront: Boolean = false,
    val openDevice: PanelDevice? = null,
)

/** Todo lo que el Panel Local muestra en un refresco, aparte de las ondas. */
data class PanelState(
    val chips: Chips,
    val signal: Status,
    val heartRate: String,
    /** Debajo de la FC: electrodos sueltos o el código de arritmia; vacío si no hay nada que decir. */
    val heartNote: String,
    val saturation: String,
    val pulse: String,
    val respiration: String,
    val pressure: PressureCard,
    val canMeasure: Boolean,
    val temperature: TempCard,
    val otoscopeTile: Tile,
    val stethoscopeTile: Tile,
    val cameraTile: Tile,
    val otoscopeStatus: Status,
    val stethoscopeStatus: Status,
    val cameraStatus: Status,
)

/**
 * El estado de la interfaz del Panel Local, sin Android: dado lo que manda
 * el runtime, la hora y lo que sabe la pantalla, dice qué mostrar. La
 * actividad sólo lo aplica a las vistas. Guarda una sola cosa entre
 * refrescos: la presión anterior del manguito, para saber si infla o mide.
 */
class PanelUi {
    private var lastCuff: Int? = null
    private var inflating = false

    fun build(
        s: LocalSnapshot,
        nowMillis: Long,
        screen: ScreenState,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PanelState {
        val (saturation, pulse) = PanelFormat.pair(s.vitals.spo2Pulse)
        val otoscopeStatus = otoscopeStatus(s, screen.otoscopeLabel)
        val stethoscopeStatus = stethoscopeStatus(s)
        return PanelState(
            chips = chips(s),
            signal = signal(s),
            heartRate = PanelFormat.value(s.vitals.heartRate),
            heartNote = heartNote(s),
            saturation = saturation,
            pulse = pulse,
            respiration = PanelFormat.value(s.vitals.respRate),
            pressure = pressure(s, nowMillis, zone),
            canMeasure = s.berryUp && !s.nibpRunning,
            temperature = temperature(s, zone),
            otoscopeTile = Tile(
                enabled = s.otoscopeAvailable || screen.openDevice == PanelDevice.OTOSCOPE,
                status = if (otoscopeStatus.tone == Tone.OK) LIVE else otoscopeStatus,
            ),
            stethoscopeTile = Tile(
                enabled = s.stethoAvailable || screen.openDevice == PanelDevice.STETHOSCOPE,
                status = stethoscopeStatus,
            ),
            cameraTile = cameraTile(screen),
            otoscopeStatus = otoscopeStatus,
            stethoscopeStatus = stethoscopeStatus,
            cameraStatus = cameraStatus(screen),
        )
    }

    /** "Inflando…" mientras la presión sube y "Midiendo…" cuando baja; sin dato, "Midiendo…". */
    fun phase(running: Boolean, cuff: Int): String {
        if (!running) {
            lastCuff = null
            inflating = false
            return ""
        }
        val previous = lastCuff
        if (cuff > 0) {
            if (previous != null && cuff > previous) inflating = true
            if (previous != null && cuff < previous) inflating = false
            lastCuff = cuff
        }
        return if (inflating) INFLATING else MEASURING
    }

    private fun pressure(s: LocalSnapshot, nowMillis: Long, zone: ZoneId): PressureCard {
        val cuff = s.nibp.cuffMmhg
        val phase = phase(s.nibpRunning, cuff)
        if (s.nibpRunning) {
            return PressureCard.Measuring(
                cuff = if (cuff > 0) cuff.toString() else PanelFormat.NONE,
                phase = phase,
                progress = (cuff / CUFF_FULL_MMHG).coerceIn(0f, 1f),
            )
        }
        val value = PanelFormat.pressure(s.vitals.nibp)
        // nibp arranca en status -1 cada vez que se abre el panel. Si después llegó algún
        // status y no hay presión, la medición terminó sin resultado: con un final de error
        // de la plaqueta o cortada por Cancelar o por el tope de tiempo.
        if (value == PanelFormat.NONE) return if (s.nibp.status >= 0) PressureCard.NoResult else PressureCard.Empty
        val mean = PanelFormat.meanPressure(s.vitals.nibp)?.let { "($it)" }.orEmpty()
        val at = s.nibpAtMillis?.let { "${PanelFormat.clock(it, zone)} · ${PanelFormat.ago(nowMillis, it)}" }.orEmpty()
        return PressureCard.Result(value, mean, at)
    }

    companion object {
        const val INFLATING = "Inflando…"
        const val MEASURING = "Midiendo…"

        /** La barra de la presión se llena a 175 mmHg, más o menos donde para de inflar. */
        const val CUFF_FULL_MMHG = 175f

        /** Velocidades del papel, en mm/s, y ganancias, en mm/mV, en el orden de los segmentos. */
        val SPEEDS = floatArrayOf(12.5f, 25f, 50f)
        val GAINS = floatArrayOf(5f, 10f, 20f)
        const val DEFAULT_SPEED = 25f
        const val DEFAULT_GAIN = 10f

        /**
         * El volumen del estetoscopio, por ahora sobre las ganancias de hoy
         * (x1, x2, x4): se muestran como 2, 5 y 10 de una escala de 0 a 10.
         */
        private val VOLUMES = mapOf(1 to 2, 2 to 5, 4 to 10)

        private val LIVE = Status("En vivo", Tone.OK)
        private val CONNECTING = Status("Conectando", Tone.WARN)
        private val CONNECTED = Status("Conectado", Tone.MUTED)
        private val DISCONNECTED = Status("desconectado", Tone.FAINT)

        /** El segmento de la velocidad: "12,5", "25", "50". */
        fun speedLabel(mmPerSecond: Float): String =
            if (mmPerSecond % 1f == 0f) mmPerSecond.toInt().toString() else mmPerSecond.toString().replace('.', ',')

        /** La ganancia en mm/mV como factor sobre la de 10 mm/mV: 5 es x0,5 y 20 es x2. */
        fun gainFactor(mmPerMv: Float): Float = mmPerMv / DEFAULT_GAIN

        fun stethoVolume(gain: Int): Int = VOLUMES[gain] ?: VOLUMES.getValue(1)

        /** La ganancia siguiente hacia arriba o hacia abajo; en los extremos se queda. */
        fun stethoGainStep(gain: Int, up: Boolean): Int {
            val gains = VOLUMES.keys.sorted()
            val index = gains.indexOf(gain).coerceAtLeast(0)
            return gains[(if (up) index + 1 else index - 1).coerceIn(0, gains.size - 1)]
        }

        /** La plaqueta está transmitiendo: conectada y con datos en los últimos 3 s. */
        fun berryLive(s: LocalSnapshot): Boolean =
            s.berryUp && s.lastDataAge != null && s.lastDataAge <= PanelFormat.LIVE_SECONDS

        fun chips(s: LocalSnapshot): Chips {
            val berry = when {
                berryLive(s) -> Link.CONNECTED
                s.berryUp -> Link.CONNECTING
                else -> Link.DISCONNECTED
            }
            val otoscope = when {
                s.otoscopeState == OtoscopePower.State.POWERING_PORT ||
                    s.otoscopeState == OtoscopePower.State.WAITING_CAMERA -> Link.CONNECTING
                s.otoscopeState == OtoscopePower.State.ON || s.otoscopePlugged -> Link.CONNECTED
                else -> Link.DISCONNECTED
            }
            val stethoscope = when {
                s.stethoStarting -> Link.CONNECTING
                s.stethoRunning || s.stethoPlugged -> Link.CONNECTED
                else -> Link.DISCONNECTED
            }
            return Chips(
                ecg = berry,
                spo2 = berry,
                pressure = berry,
                temperature = if (s.thermoConnected) Link.CONNECTED else Link.DISCONNECTED,
                otoscope = otoscope,
                stethoscope = stethoscope,
            )
        }

        /** La señal del ECG: sin datos o con los electrodos sueltos no hay señal. */
        fun signal(s: LocalSnapshot): Status = when {
            !berryLive(s) || s.ecgInfo.leadOff != false -> Status("Sin señal", Tone.FAINT)
            s.ecgInfo.weakSignal == true -> Status("Señal débil", Tone.WARN)
            else -> Status("Señal buena", Tone.OK)
        }

        fun heartNote(s: LocalSnapshot): String = when {
            s.ecgInfo.leadOff == true -> "Electrodos sueltos"
            else -> PanelFormat.arrhythmia(s.ecgInfo.hrv).orEmpty()
        }

        fun temperature(s: LocalSnapshot, zone: ZoneId = ZoneId.systemDefault()): TempCard {
            if (!s.thermoConnected) return TempCard.Disconnected
            val value = PanelFormat.value(s.thermoReading)
            if (value == PanelFormat.NONE) return TempCard.Waiting
            val at = s.thermoAtMillis?.let { "IR · ${PanelFormat.clock(it, zone)}" } ?: "IR"
            return TempCard.Reading(value, at)
        }

        /** En el encabezado del panel: "En vivo · UVC" con la imagen abierta. */
        fun otoscopeStatus(s: LocalSnapshot, label: String?): Status = when (s.otoscopeState) {
            OtoscopePower.State.POWERING_PORT, OtoscopePower.State.WAITING_CAMERA -> CONNECTING
            OtoscopePower.State.POWERING_OFF -> Status("Apagando…", Tone.MUTED)
            OtoscopePower.State.ON ->
                if (label == null) CONNECTING else Status("En vivo · ${label.removePrefix("por ")}", Tone.OK)
            OtoscopePower.State.OFF -> if (s.otoscopeAvailable) Status("Apagado", Tone.MUTED) else DISCONNECTED
        }

        fun stethoscopeStatus(s: LocalSnapshot): Status = when {
            s.stethoRunning -> Status("Escuchando", Tone.OK)
            s.stethoStarting -> CONNECTING
            s.stethoAvailable -> CONNECTED
            else -> DISCONNECTED
        }

        fun cameraTile(screen: ScreenState): Tile = when (screen.camera) {
            CameraState.MISSING -> Tile(enabled = screen.openDevice == PanelDevice.CAMERA, status = DISCONNECTED)
            CameraState.PAUSED -> Tile(enabled = true, status = Status("En pausa", Tone.WARN))
            else -> Tile(enabled = true, status = Status("Lista", Tone.MUTED))
        }

        fun cameraStatus(screen: ScreenState): Status = when (screen.camera) {
            CameraState.LIVE -> Status("En vivo · ${if (screen.cameraFront) "frontal" else "trasera"}", Tone.OK)
            CameraState.STARTING -> CONNECTING
            CameraState.PAUSED -> Status("En pausa", Tone.WARN)
            CameraState.UNAVAILABLE -> Status("Sin imagen", Tone.MUTED)
            CameraState.MISSING -> DISCONNECTED
        }
    }
}
