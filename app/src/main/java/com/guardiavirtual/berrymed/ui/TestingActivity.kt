// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.guardiavirtual.berrymed.i18n.AppLocale
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.databinding.ActivityTestingBinding
import com.guardiavirtual.berrymed.databinding.ItemTestCardBinding
import com.guardiavirtual.berrymed.i18n.resolve
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.service.TestingSession.Measure
import com.guardiavirtual.berrymed.service.TestingSession.Verdict
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TestingSnapshot
import com.guardiavirtual.berrymed.tele.Instrument
import com.guardiavirtual.berrymed.tele.RoomState
import com.guardiavirtual.berrymed.tele.TeleView
import com.guardiavirtual.berrymed.ui.panel.panelFormat
import kotlin.concurrent.thread

/**
 * Panel Testing: mira lo que el servicio ya está leyendo de cada equipo, sin
 * abrir puertos propios. Mientras la prueba corre no sale ningún POST, y al
 * salir de la pantalla la prueba se corta, igual que en el monitor de Windows.
 */
class TestingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTestingBinding
    private val handler = Handler(Looper.getMainLooper())

    /** El idioma de los números y la hora: el de los textos (del diccionario), no el de la tablet. */
    private val numbers by lazy { panelFormat().locale }
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

    /** Toda la pantalla a la escala de diseño: ver [UiScale]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.scaled(AppLocale.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.ecgCard.setup(R.string.testing_card_ecg, R.color.ecg, R.string.testing_card_ecg_check)
        binding.spo2Card.setup(R.string.testing_card_spo2, R.color.spo2, R.string.testing_card_spo2_check)
        binding.respCard.setup(R.string.testing_card_resp, R.color.resp, R.string.testing_card_resp_check)
        binding.tempCard.setup(R.string.testing_card_temp, R.color.temp, R.string.testing_card_temp_check)
        binding.nibpCard.setup(R.string.testing_card_nibp, R.color.pa, R.string.testing_card_nibp_measure, R.string.testing_card_nibp_stop)
        binding.thermoCard.setup(R.string.testing_card_thermo, R.color.temp, null)

        // Volver corta la prueba en onStop, igual que el gesto de atrás.
        binding.backButton.setOnClickListener { finish() }
        binding.startButton.setOnClickListener {
            val blocked = runtime().startTesting()
            binding.testMessage.text = blocked?.resolve(this).orEmpty()
            if (blocked == null) measureCards.values.forEach { it.cardResult.text = "" }
        }
        binding.stopButton.setOnClickListener {
            runtime().stopTesting()
            binding.testMessage.text = ""
        }
        binding.allButton.setOnClickListener { Measure.entries.forEach(::check) }
        measureCards.forEach { (measure, card) -> card.cardButton.setOnClickListener { check(measure) } }

        binding.nibpCard.cardButton.setOnClickListener {
            binding.nibpCard.cardResult.text = runtime().testNibpStart().message.resolve(this)
        }
        binding.nibpCard.cardButton2.setOnClickListener {
            binding.nibpCard.cardResult.text = runtime().testNibpStop().message.resolve(this)
        }
        binding.ykushReadButton.setOnClickListener {
            binding.ykushMessage.setText(R.string.testing_ykush_reading)
            thread(name = "berry-test-ykush") {
                val text = runtime().testYkushRead()
                handler.post { binding.ykushMessage.text = text.resolve(this) }
            }
        }
        binding.ykushSaveBootButton.setOnClickListener {
            binding.ykushMessage.setText(R.string.testing_ykush_saving)
            thread(name = "berry-test-ykush") {
                val text = runtime().testYkushSaveBoot()
                handler.post { binding.ykushMessage.text = text.resolve(this) }
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

    /** Mientras se ve Testing, el reposo del hub no apaga ni enciende puertos (salvo el termómetro). */
    override fun onStart() {
        super.onStart()
        runtime().setTestingScreenOpen(true)
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
        runtime().setTestingScreenOpen(false)
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

    /** Enciende o apaga un puerto del YKUSH3. Tarda hasta cuatro segundos, así que va en otro hilo. */
    private fun switchPort(port: Int, on: Boolean) {
        binding.ykushMessage.text =
            getString(if (on) R.string.testing_ykush_turning_on else R.string.testing_ykush_turning_off, port)
        thread(name = "berry-test-ykush") {
            val text = runtime().testYkushSet(port, on)
            handler.post { binding.ykushMessage.text = text.resolve(this) }
        }
    }

    private fun render(s: TestingSnapshot) {
        binding.testStatus.setText(
            when {
                s.testing -> R.string.testing_status_running
                s.sessionActive -> R.string.testing_status_session
                else -> R.string.testing_status_stopped
            },
        )
        // El punto del estado: verde probando, ámbar con una consulta enviando, gris detenida.
        val statusColor = getColor(
            when {
                s.testing -> R.color.ok
                s.sessionActive -> R.color.warn
                else -> R.color.muted
            },
        )
        binding.testStatus.setTextColor(statusColor)
        TextViewCompat.setCompoundDrawableTintList(binding.testStatus, ColorStateList.valueOf(statusColor))

        val age = s.lastDataAge
        val berryLive = s.berryUp && age != null && age <= LIVE_SECONDS
        dot(binding.berryDot, berryLive)
        val berryPort = s.berryPort ?: "?"
        binding.berryLine.text = when {
            !s.berryUp -> getString(R.string.testing_berry_disconnected)
            age == null -> getString(R.string.testing_berry_line_no_data, berryPort, s.rates.badChecksum)
            else -> getString(R.string.testing_berry_line, berryPort, "%.1f".format(numbers, age), s.rates.badChecksum)
        }

        val vitals = s.stats.vitals
        val info = s.stats.ecgInfo
        val rates = s.rates
        binding.ecgCard.show(
            vitals.heartRate,
            getString(
                when (info.leadOff) {
                    true -> R.string.testing_ecg_detail_leads_off
                    false -> R.string.testing_ecg_detail_leads_on
                    null -> R.string.testing_ecg_detail_leads_unknown
                },
                rates.ecg,
                rates.peaks,
            ),
            rates.ecg > 0,
        )
        binding.spo2Card.show(
            vitals.spo2Pulse,
            getString(
                if (vitals.spo2Pulse.first().isDigit()) R.string.testing_spo2_detail_finger else R.string.testing_spo2_detail_no_finger,
                rates.spo2Wave,
            ),
            rates.spo2Wave > 0,
        )
        binding.respCard.show(vitals.respRate, getString(R.string.testing_resp_detail, rates.respWave), rates.respWave > 0)
        binding.tempCard.show(
            vitals.temperature,
            getString(
                if (vitals.temperature.first().isDigit()) R.string.testing_temp_detail_connected else R.string.testing_temp_detail_disconnected,
                rates.temperature,
            ),
            rates.temperature > 0,
        )
        measureCards.forEach { (measure, card) -> s.verdicts[measure]?.let { card.verdict(it) } }

        val frames = s.stats.frames
        binding.nibpCard.show(
            vitals.nibp,
            when {
                s.nibpRunning -> getString(R.string.testing_nibp_detail_measuring, frames.cuffMmhg)
                frames.nibpStatus < 0 -> getString(R.string.testing_nibp_detail_idle, frames.cuffMmhg)
                else -> getString(R.string.testing_nibp_detail_idle_last, frames.cuffMmhg, frames.nibpStatus)
            },
            s.nibpRunning,
        )

        val thermoLive = s.thermoAge != null && s.thermoAge <= LIVE_SECONDS.toLong()
        binding.thermoCard.show(
            s.thermoReading ?: "—",
            if (!s.thermoConnected) {
                getString(R.string.testing_thermo_disconnected)
            } else {
                val port = s.thermoPort ?: "?"
                val line = s.thermoLine.ifBlank { "—" }
                s.thermoAge?.let { getString(R.string.testing_thermo_detail, port, it, line) }
                    ?: getString(R.string.testing_thermo_detail_never, port, line)
            },
            thermoLive,
        )
        if (s.thermoConnected && s.thermoReading == null) {
            binding.thermoCard.cardResult.setText(R.string.testing_thermo_trigger)
        } else if (!s.thermoConnected) {
            binding.thermoCard.cardResult.text = ""
        }

        dot(binding.ykushDot, s.ykushPlugged)
        binding.ykushStatus.setText(if (s.ykushPlugged) R.string.testing_ykush_connected else R.string.testing_ykush_disconnected)
        s.ykushPorts.forEachIndexed { index, port ->
            val row = ykushPorts[index]
            row.portLabel.text = getString(R.string.testing_ykush_port, port.port, port.role.resolve(this))
            row.portState.setText(
                when (port.on) {
                    true -> R.string.testing_ykush_port_on
                    false -> R.string.testing_ykush_port_off
                    null -> R.string.testing_ykush_port_unknown
                },
            )
            row.portState.setTextColor(getColor(if (port.on == true) R.color.ok else R.color.muted))
        }

        binding.usbList.text =
            s.inventory.usb.map { it.resolve(this) }.ifEmpty { listOf(getString(R.string.testing_usb_none)) }.joinToString("\n")
        binding.audioList.text = s.inventory.audio.joinToString("\n") { it.resolve(this) }
        binding.cameraList.text = s.inventory.cameras.joinToString("\n") { it.resolve(this) }

        renderTele(s.tele)

        binding.previewUrl.text = s.url?.let { getString(R.string.testing_preview_url, it) } ?: getString(R.string.testing_preview_url_missing)
        lastPreview = s.preview
        binding.previewBody.text = s.preview?.let { body ->
            getString(R.string.testing_preview_body, body.take(PREVIEW_CHARS), "%.1f".format(numbers, body.length / 1024.0))
        } ?: getString(R.string.testing_preview_empty)

        if (!consolePaused) binding.consoleText.text = s.console.take(CONSOLE_LINES).joinToString("\n")
    }

    private fun renderTele(t: TeleView) {
        val status = t.status
        dot(binding.teleDot, status.room == RoomState.CONNECTED && status.instrument != null)
        val room = getString(
            when (status.room) {
                RoomState.DISCONNECTED -> R.string.testing_tele_room_disconnected
                RoomState.CONNECTING -> R.string.testing_tele_room_connecting
                RoomState.CONNECTED -> R.string.testing_tele_room_connected
                RoomState.ERROR -> R.string.testing_tele_room_error
            },
        )
        binding.teleState.text = if (status.manual) getString(R.string.testing_tele_state_manual, room) else room
        val service = when {
            t.serviceCamera && t.serviceMicrophone -> R.string.testing_tele_service_camera_mic
            t.serviceCamera -> R.string.testing_tele_service_camera
            t.serviceMicrophone -> R.string.testing_tele_service_mic
            else -> R.string.testing_tele_service_none
        }
        // Nunca el token: sólo cuándo se vio y cuándo vence para entrar.
        val token = when {
            status.tokenSeenAtMillis == 0L -> null
            status.tokenExpiresAtMillis == null -> getString(R.string.testing_tele_token_seen, clock(status.tokenSeenAtMillis))
            else -> getString(
                R.string.testing_tele_token_seen_expires,
                clock(status.tokenSeenAtMillis),
                clock(status.tokenExpiresAtMillis),
            )
        }
        val publishes = when (status.instrument) {
            null -> getString(R.string.testing_tele_publishes_nothing)
            Instrument.OTOSCOPE -> getString(
                R.string.testing_tele_publishes_otoscope,
                when (t.videoPath) {
                    null -> ""
                    OtoscopePower.Path.UVC -> getString(R.string.testing_tele_path_uvc)
                    OtoscopePower.Path.CAMERA2 -> getString(R.string.testing_tele_path_camera2)
                },
                t.videoSize.orEmpty(),
                "%.1f".format(numbers, t.framesPerSecond),
            )
            Instrument.STETHOSCOPE -> getString(R.string.testing_tele_publishes_stetho, t.levelPercent)
        }
        val android = if (status.instrument != Instrument.STETHOSCOPE) {
            null
        } else {
            getString(
                when (t.silenced) {
                    true -> R.string.testing_tele_android_silenced
                    false -> R.string.testing_tele_android_not_silenced
                    null -> R.string.testing_tele_android_unknown
                },
            )
        }
        binding.teleDetail.text = listOfNotNull(
            getString(service, t.serviceTypes),
            t.callAudio.resolve(this),
            // La sala, su nombre y el token, cada uno una frase entera, juntos en un renglón.
            listOfNotNull(getString(R.string.testing_tele_room, room), status.roomName, token).joinToString(" · "),
            publishes,
            android,
            status.message?.let { getString(R.string.testing_tele_last_error, it.resolve(this)) },
        ).joinToString("\n")
    }

    private fun clock(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm", numbers).format(java.util.Date(millis))

    /** El título en el color fijo del parámetro, como en el Panel Local. */
    private fun ItemTestCardBinding.setup(title: Int, color: Int, button: Int?, button2: Int? = null) {
        cardTitle.setText(title)
        cardTitle.setTextColor(getColor(color))
        if (button == null) cardButton.visibility = View.GONE else cardButton.setText(button)
        if (button2 != null) {
            cardButton2.setText(button2)
            cardButton2.visibility = View.VISIBLE
        }
    }

    private fun ItemTestCardBinding.show(value: String, detail: String, live: Boolean) {
        cardValue.text = value
        cardDetail.text = detail
        dot(cardDot, live)
    }

    private fun ItemTestCardBinding.verdict(verdict: Verdict) {
        cardResult.text = verdict.text.resolve(this@TestingActivity)
        cardResult.setTextColor(
            getColor(
                when (verdict.ok) {
                    true -> R.color.ok
                    false -> R.color.test_fail
                    null -> R.color.muted
                },
            ),
        )
    }

    private fun dot(view: View, live: Boolean) {
        view.backgroundTintList = ColorStateList.valueOf(getColor(if (live) R.color.ok else R.color.line3))
    }

    companion object {
        /** Igual que el monitor de Windows: un equipo transmite si mandó algo en los últimos 3 s. */
        private const val LIVE_SECONDS = 3.0
        private const val PREVIEW_CHARS = 400
        private const val CONSOLE_LINES = 60
    }
}
