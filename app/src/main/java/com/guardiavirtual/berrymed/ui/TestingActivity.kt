// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.databinding.ActivityTestingBinding
import com.guardiavirtual.berrymed.databinding.ItemTestCardBinding
import com.guardiavirtual.berrymed.service.TestingSession.Measure
import com.guardiavirtual.berrymed.service.TestingSession.Verdict
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TestingSnapshot
import com.guardiavirtual.berrymed.tele.Instrument
import com.guardiavirtual.berrymed.tele.RoomState
import com.guardiavirtual.berrymed.tele.TeleView
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Panel Testing: mira lo que el servicio ya está leyendo de cada equipo, sin
 * abrir puertos propios. Mientras la prueba corre no sale ningún POST, y al
 * salir de la pantalla la prueba se corta, igual que en el monitor de Windows.
 */
class TestingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTestingBinding
    private val handler = Handler(Looper.getMainLooper())
    private var consolePaused = false
    private var lastPreview: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if (::binding.isInitialized) render(runtime().testingSnapshot())
            handler.postDelayed(this, 500)
        }
    }

    private val ykushPorts by lazy { listOf(binding.ykushPort1, binding.ykushPort2, binding.ykushPort3) }

    private val measureCards by lazy {
        mapOf(
            Measure.Ecg to binding.ecgCard,
            Measure.Spo2 to binding.spo2Card,
            Measure.Resp to binding.respCard,
            Measure.Temp to binding.tempCard,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ecgCard.setup("ECG", "Probar ECG")
        binding.spo2Card.setup("SpO₂ / pulso", "Probar SpO₂")
        binding.respCard.setup("Respiración", "Probar respiración")
        binding.tempCard.setup("Temperatura (plaqueta)", "Probar temperatura")
        binding.nibpCard.setup("Presión", "Medir", "Parar")
        binding.thermoCard.setup("Termómetro IR", null)

        // Volver corta la prueba en onStop, igual que el gesto de atrás.
        binding.backButton.setOnClickListener { finish() }
        binding.startButton.setOnClickListener {
            val blocked = runtime().startTesting()
            binding.testMessage.text = blocked.orEmpty()
            if (blocked == null) measureCards.values.forEach { it.cardResult.text = "" }
        }
        binding.stopButton.setOnClickListener {
            runtime().stopTesting()
            binding.testMessage.text = ""
        }
        binding.allButton.setOnClickListener { Measure.entries.forEach(::check) }
        measureCards.forEach { (measure, card) -> card.cardButton.setOnClickListener { check(measure) } }

        binding.nibpCard.cardButton.setOnClickListener {
            binding.nibpCard.cardResult.text = runtime().testNibpStart()
        }
        binding.nibpCard.cardButton2.setOnClickListener {
            binding.nibpCard.cardResult.text = runtime().testNibpStop()
        }
        binding.ykushReadButton.setOnClickListener {
            binding.ykushMessage.text = "Leyendo…"
            thread(name = "berry-test-ykush") {
                val text = runtime().testYkushRead()
                handler.post { binding.ykushMessage.text = text }
            }
        }
        ykushPorts.forEachIndexed { index, row ->
            row.portOn.setOnClickListener { switchPort(index + 1, on = true) }
            row.portOff.setOnClickListener { switchPort(index + 1, on = false) }
        }

        binding.copyButton.setOnClickListener {
            val body = lastPreview ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("metrics", body))
            binding.testMessage.setText(R.string.testing_copied)
        }
        binding.pauseButton.setOnClickListener {
            consolePaused = !consolePaused
            binding.pauseButton.setText(if (consolePaused) R.string.testing_resume else R.string.testing_pause)
        }
        binding.clearButton.setOnClickListener {
            runtime().console.clear()
            binding.consoleText.text = ""
        }

        binding.teleOtoscopeButton.setOnClickListener { publishManually(Instrument.OTOSCOPE) }
        binding.teleStethoButton.setOnClickListener { publishManually(Instrument.STETHOSCOPE) }
        binding.teleStopButton.setOnClickListener { runtime().teleManualStop() }
    }

    /** La prueba manual: el mismo streamer, con el servidor y el token escritos acá. */
    private fun publishManually(instrument: Instrument) {
        runtime().teleManualStart(
            binding.teleServer.text?.toString().orEmpty(),
            binding.teleToken.text?.toString().orEmpty(),
            instrument,
        )
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    /** Con Testing adelante el servicio suma cámara y micrófono, para la prueba de la teleconsulta. */
    override fun onResume() {
        super.onResume()
        MonitorService.promote(this)
    }

    /**
     * Al cerrar la pantalla (Volver o el gesto de atrás) la prueba se corta
     * enseguida: onStop puede llegar varios segundos después.
     */
    override fun onPause() {
        if (isFinishing) runtime().stopTesting()
        super.onPause()
    }

    /** Salir de la pantalla corta la prueba: no queda el envío frenado ni el manguito inflado. */
    override fun onStop() {
        handler.removeCallbacks(tick)
        runtime().stopTesting()
        // La prueba manual de la teleconsulta sigue: sirve justamente para probar con Chrome adelante.
        super.onStop()
    }

    private fun runtime() = (application as BerryMedApp).runtime

    private fun check(measure: Measure) {
        val card = measureCards.getValue(measure)
        if (!runtime().testing) {
            card.cardResult.setTextColor(getColor(R.color.test_fail))
            card.cardResult.setText(R.string.testing_first)
            return
        }
        runtime().checkMeasure(measure)
    }

    /** Enciende o apaga un puerto del YKUSH3. Tarda hasta un segundo, así que va en otro hilo. */
    private fun switchPort(port: Int, on: Boolean) {
        binding.ykushMessage.text = if (on) "Encendiendo el puerto $port…" else "Apagando el puerto $port…"
        thread(name = "berry-test-ykush") {
            val text = runtime().testYkushSet(port, on)
            handler.post { binding.ykushMessage.text = text }
        }
    }

    private fun render(s: TestingSnapshot) {
        binding.testStatus.text = when {
            s.testing -> "Probando · sin envío a la API"
            s.sessionActive -> "Hay una consulta enviando"
            else -> "Detenida"
        }

        val age = s.lastDataAge
        val berryLive = s.berryUp && age != null && age <= LIVE_SECONDS
        dot(binding.berryDot, berryLive)
        binding.berryLine.text = if (!s.berryUp) {
            "Plaqueta PM6750 sin conectar"
        } else {
            val ageText = if (age == null) "sin datos" else "hace ${"%.1f".format(Locale("es"), age)} s"
            "Plaqueta PM6750 · ${s.berryPort ?: "?"} · último dato $ageText · tramas rotas ${s.rates.badChecksum}/s"
        }

        val vitals = s.stats.vitals
        val info = s.stats.ecgInfo
        val rates = s.rates
        binding.ecgCard.show(
            vitals.heartRate,
            "${rates.ecg} muestras/s × 7 deriv.\nlatidos ${rates.peaks}/s · electrodos: " +
                when (info.leadOff) {
                    true -> "sueltos"
                    false -> "conectados"
                    null -> "—"
                },
            rates.ecg > 0,
        )
        binding.spo2Card.show(
            vitals.spo2Pulse,
            "onda ${rates.spo2Wave} muestras/s\ndedo: " + if (vitals.spo2Pulse.first().isDigit()) "detectado" else "no detectado",
            rates.spo2Wave > 0,
        )
        binding.respCard.show(vitals.respRate, "onda ${rates.respWave} muestras/s", rates.respWave > 0)
        binding.tempCard.show(
            vitals.temperature,
            "tramas ${rates.temperature}/s\nsensor: " + if (vitals.temperature.first().isDigit()) "conectado" else "sin conectar",
            rates.temperature > 0,
        )
        measureCards.forEach { (measure, card) -> s.verdicts[measure]?.let { card.verdict(it) } }

        val frames = s.stats.frames
        binding.nibpCard.show(
            vitals.nibp,
            "manguito ${frames.cuffMmhg} mmHg\nestado: " + when {
                s.nibpRunning -> "midiendo"
                frames.nibpStatus < 0 -> "en espera"
                else -> "en espera (último 0x%02X)".format(frames.nibpStatus)
            },
            s.nibpRunning,
        )

        val thermoLive = s.thermoAge != null && s.thermoAge <= LIVE_SECONDS.toLong()
        binding.thermoCard.show(
            s.thermoReading ?: "—",
            if (!s.thermoConnected) {
                "Termómetro sin conectar"
            } else {
                val since = s.thermoAge?.let { "hace $it s" } ?: "sin mediciones"
                "${s.thermoPort ?: "?"} · $since\núltima línea: ${s.thermoLine.ifBlank { "—" }}"
            },
            thermoLive,
        )
        if (s.thermoConnected && s.thermoReading == null) {
            binding.thermoCard.cardResult.text = "Apretá el gatillo para medir"
        } else if (!s.thermoConnected) {
            binding.thermoCard.cardResult.text = ""
        }

        dot(binding.ykushDot, s.ykushPlugged)
        binding.ykushStatus.text = if (s.ykushPlugged) "Conectado · 04D8:F11B" else "Sin conectar"
        s.ykushPorts.forEachIndexed { index, port ->
            val row = ykushPorts[index]
            row.portLabel.text = "Puerto ${port.port} · ${port.role}"
            row.portState.text = when (port.on) {
                true -> "encendido"
                false -> "apagado"
                null -> "sin leer"
            }
            row.portState.setTextColor(getColor(if (port.on == true) R.color.status_live else R.color.vital_dim))
        }

        binding.usbList.text = s.inventory.usb.ifEmpty { listOf("Android no ve ningún dispositivo USB") }.joinToString("\n")
        binding.audioList.text = s.inventory.audio.joinToString("\n")
        binding.cameraList.text = s.inventory.cameras.joinToString("\n")

        renderTele(s.tele)

        binding.previewUrl.text = s.url?.let { "POST $it" } ?: "Falta configurar la URL o el tótem"
        lastPreview = s.preview
        binding.previewBody.text = s.preview?.let { body ->
            "${body.take(PREVIEW_CHARS)}… · ${"%.1f".format(Locale("es"), body.length / 1024.0)} KB"
        } ?: "Sin datos para enviar"

        if (!consolePaused) binding.consoleText.text = s.console.take(CONSOLE_LINES).joinToString("\n")
    }

    private fun renderTele(t: TeleView) {
        val status = t.status
        dot(binding.teleDot, status.room == RoomState.CONNECTED && status.instrument != null)
        binding.teleState.text = status.room.label + if (status.manual) " · prueba manual" else ""
        binding.teleDetail.text = buildString {
            append("Servicio: ${t.serviceTypes} · cámara ${yesNo(t.serviceCamera)} · micrófono ${yesNo(t.serviceMicrophone)}\n")
            append("Sala: ${status.room.label}")
            status.encounterId?.let { append(" · encuentro $it") }
            append("\nPublica: ")
            append(
                when (status.instrument) {
                    null -> "nada"
                    Instrument.OTOSCOPE ->
                        "otoscopio ${t.videoPath.orEmpty()} ${t.videoSize.orEmpty()} · " +
                            "${"%.1f".format(Locale("es"), t.framesPerSecond)} cuadros/s"
                    Instrument.STETHOSCOPE -> "estetoscopio · nivel ${t.levelPercent} %"
                },
            )
            if (status.instrument == Instrument.STETHOSCOPE) {
                append(
                    "\nAndroid: " + when (t.silenced) {
                        true -> "SILENCIA la captura (otra app tiene el micrófono)"
                        false -> "la captura no está silenciada"
                        null -> "sin datos de la captura"
                    },
                )
            }
            if (status.message.isNotBlank()) append("\nÚltimo error: ${status.message}")
        }
    }

    private fun yesNo(value: Boolean) = if (value) "sí" else "no"

    private fun ItemTestCardBinding.setup(title: String, button: String?, button2: String? = null) {
        cardTitle.text = title
        if (button == null) cardButton.visibility = View.GONE else cardButton.text = button
        if (button2 != null) {
            cardButton2.text = button2
            cardButton2.visibility = View.VISIBLE
        }
    }

    private fun ItemTestCardBinding.show(value: String, detail: String, live: Boolean) {
        cardValue.text = value
        cardDetail.text = detail
        dot(cardDot, live)
    }

    private fun ItemTestCardBinding.verdict(verdict: Verdict) {
        cardResult.text = verdict.message
        cardResult.setTextColor(
            getColor(
                when (verdict.ok) {
                    true -> R.color.status_live
                    false -> R.color.test_fail
                    null -> R.color.vital_dim
                },
            ),
        )
    }

    private fun dot(view: View, live: Boolean) {
        view.backgroundTintList = ColorStateList.valueOf(getColor(if (live) R.color.status_live else R.color.vital_dim))
    }

    companion object {
        /** Igual que el monitor de Windows: un equipo transmite si mandó algo en los últimos 3 s. */
        private const val LIVE_SECONDS = 3.0
        private const val PREVIEW_CHARS = 400
        private const val CONSOLE_LINES = 60
    }
}
