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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.databinding.ActivityTestingBinding
import com.guardiavirtual.berrymed.databinding.ItemTestCardBinding
import com.guardiavirtual.berrymed.service.TestingSession.Measure
import com.guardiavirtual.berrymed.service.TestingSession.Verdict
import com.guardiavirtual.berrymed.service.TestingSnapshot
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
        binding.relayCard.setup("Relé USBRelay2", "Leer estado", "Cortar 5 s")

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
        binding.relayCard.cardButton.setOnClickListener {
            binding.relayCard.cardResult.text = "Leyendo…"
            thread(name = "berry-test-relay") {
                val text = runtime().testRelayState()
                handler.post { binding.relayCard.cardResult.text = text }
            }
        }
        binding.relayCard.cardButton2.setOnClickListener { confirmCut() }

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
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    /** Salir de la pantalla corta la prueba: no queda el envío frenado ni el manguito inflado. */
    override fun onStop() {
        handler.removeCallbacks(tick)
        runtime().stopTesting()
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

    private fun confirmCut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.testing_cut_title)
            .setMessage(R.string.testing_cut_body)
            .setNegativeButton(R.string.testing_cancel, null)
            .setPositiveButton(R.string.testing_cut_confirm) { _, _ ->
                binding.relayCard.cardResult.text = "Cortando…"
                thread(name = "berry-test-cut") {
                    val text = runtime().testRelayCut()
                    handler.post { binding.relayCard.cardResult.text = text }
                }
            }
            .show()
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

        binding.relayCard.show(
            if (s.relayPlugged) "Presente" else "Sin conectar",
            "16C0:05DF\núltimo corte: " + (s.lastCutAge?.let { "hace ${it / 60} min" } ?: "ninguno desde que arrancó"),
            s.relayPlugged,
        )

        binding.previewUrl.text = s.url?.let { "POST $it" } ?: "Falta configurar la URL o el tótem"
        lastPreview = s.preview
        binding.previewBody.text = s.preview?.let { body ->
            "${body.take(PREVIEW_CHARS)}… · ${"%.1f".format(Locale("es"), body.length / 1024.0)} KB"
        } ?: "Sin datos para enviar"

        if (!consolePaused) binding.consoleText.text = s.console.take(CONSOLE_LINES).joinToString("\n")
    }

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
