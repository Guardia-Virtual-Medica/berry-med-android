// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.ConfigStore
import com.guardiavirtual.berrymed.net.MetricsPoster
import com.guardiavirtual.berrymed.net.PostOutcome
import com.guardiavirtual.berrymed.net.SessionPusher
import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.guardiavirtual.berrymed.protocol.applyThermometer
import com.guardiavirtual.berrymed.protocol.isValidSample
import com.guardiavirtual.berrymed.protocol.metricsEnvelope
import com.guardiavirtual.berrymed.protocol.sessionExpired
import com.guardiavirtual.berrymed.protocol.summarize
import com.guardiavirtual.berrymed.usb.BerryLink
import com.guardiavirtual.berrymed.usb.ThermometerLink
import com.guardiavirtual.berrymed.usb.UsbHub
import com.guardiavirtual.berrymed.usb.UsbRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TotemRuntime(context: Context) {
    private val appContext = context.applicationContext
    val state = MutableStateFlow(TotemState())
    val store = ConfigStore(appContext)
    private val parser = BmDataParser()
    private val hub = UsbHub(appContext)
    private val relay = UsbRelay(hub)
    private val berry = BerryLink(hub, relay, parser, { config.tuning() }, ::log)
    private val thermo = ThermometerLink(hub, { berry.connected }, ::log)
    private val poster = MetricsPoster()
    private val sessionLock = Any()

    @Volatile var config: BerryConfig = BerryConfig()
    @Volatile var sessionActive: Boolean = false
    @Volatile private var sessionStartNano: Long = 0L
    @Volatile var lastPost: String = "Todavía no se envió"
    @Volatile var pusherUp: Boolean = false

    var onNotification: (text: String) -> Unit = {}

    private val pusher = SessionPusher(::log, object : SessionPusher.Listener {
        override fun onStart() = startSession()
        override fun onStop() = stopSession("evento stop")
        override fun onBloodPressure() {
            // Sólo si arrancó: un pedido rechazado no borra la presión que se está mostrando.
            if (berry.startNibp()) parser.resetNibp()
        }
        override fun onConnection(connected: Boolean) {
            pusherUp = connected
        }
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        parser.onNibp = { status, _, _, _, _ -> berry.onNibpStatus(status) }
        scope.launch {
            store.flow.collect { saved ->
                config = saved
                pusher.apply(saved)
            }
        }
        thread(name = "berry-usb", isDaemon = true) { berry.run() }
        thread(name = "berry-thermo", isDaemon = true) { thermo.run() }
        thread(name = "berry-post", isDaemon = true) { postLoop() }
        thread(name = "berry-watch", isDaemon = true) { watchLoop() }
    }

    fun requestUsbPermissionAgain() {
        hub.forgetAsked()
    }

    fun onUsbPermission(device: UsbDevice?, granted: Boolean) {
        if (device != null) {
            hub.onPermissionResult(device, granted)
            log("[USB] Permiso ${if (granted) "aceptado" else "rechazado"} para ${device.deviceName}")
        }
        publish()
    }

    /**
     * Lo que tiene que seguir andando aunque un POST tarde: los pedidos de
     * permiso USB, la reconexión de Pusher y la pantalla.
     */
    private fun watchLoop() {
        while (true) {
            guarded("permisos USB") { hub.requestMissingPermissions() }
            guarded("Pusher") { pusher.keepAlive() }
            guarded("pantalla") { publish() }
            sleep(1_000)
        }
    }

    /** Una tarea que falla no frena a las otras del mismo hilo. */
    private fun guarded(task: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            log("[WATCH] Falló $task: $error")
        }
    }

    private fun postLoop() {
        while (true) {
            try {
                stopSessionIfExpired()
                val window = synchronized(sessionLock) {
                    if (!sessionActive) null else parser.takePayload()
                }
                if (window != null) {
                    val cfg = config
                    applyThermometer(window.vitalSigns, thermo.latest())
                    val url = cfg.metricsUrl()
                    if (cfg.postReady() && url != null && isValidSample(window)) {
                        log("[DATA] ${summarize(window)}")
                        lastPost = when (val outcome = poster.post(url, cfg.apiUsername, cfg.apiPassword, metricsEnvelope(System.currentTimeMillis(), window))) {
                            PostOutcome.Ok -> "Último envío correcto"
                            is PostOutcome.Http -> "La API respondió ${outcome.code}"
                            is PostOutcome.Failed -> "No se pudo enviar: ${outcome.message}"
                        }
                    }
                }
            } catch (error: Exception) {
                log("[DATA] Error en el envío: $error")
                lastPost = "Error al enviar: ${error.message ?: error.javaClass.simpleName}"
            }
            sleep(1_000)
        }
    }

    private fun startSession() {
        synchronized(sessionLock) {
            berry.stopNibp()
            parser.resetData()
            thermo.resetLatest()
            sessionStartNano = System.nanoTime()
            sessionActive = true
        }
        log("[SESIÓN] Envío iniciado")
        publish()
    }

    /** Se mira dentro del lock: un inicio que llega justo antes no se corta. */
    private fun stopSessionIfExpired() {
        val minutes = config.maxSessionMinutes
        stopSession("sin stop tras $minutes min") {
            sessionExpired(sessionActive, sessionStartNano, System.nanoTime(), minutes)
        }
    }

    private fun stopSession(reason: String, stillDue: () -> Boolean = { true }) {
        synchronized(sessionLock) {
            if (!stillDue()) return
            sessionActive = false
            sessionStartNano = 0L
            berry.stopNibp()
            parser.resetData()
            thermo.resetLatest()
        }
        log("[SESIÓN] Envío detenido ($reason)")
        publish()
    }

    fun publish() {
        val vitals = parser.peekVitals()
        val temperature = thermo.postedTemperature(vitals.temperature)
        val seen = berry.lastDataAgeSeconds() != null
        val display = { raw: String -> if (!seen) "—" else raw }
        val berryUp = berry.connected
        val active = sessionActive
        val notification = when {
            hub.permissionMissing() -> "Falta el permiso USB"
            !berryUp -> "Esperando la plaqueta"
            active -> "Plaqueta conectada, enviando mediciones"
            else -> "Plaqueta conectada, sesión detenida"
        }
        val age = berry.lastDataAgeSeconds()
        val lastData = if (age == null) {
            "Último dato: sin datos"
        } else {
            "Último dato: hace ${"%.1f".format(Locale("es"), age)} s"
        }
        val others = buildString {
            append(
                when {
                    !thermo.connected -> "Termómetro sin conectar. "
                    thermo.measured -> "Termómetro conectado. "
                    else -> "Termómetro conectado, sin mediciones todavía. "
                },
            )
            append(if (relay.plugged()) "Relé presente. " else "Relé sin conectar. ")
            append(if (active) "La sesión está enviando." else "La sesión está detenida.")
            val missing = config.missingFields()
            if (missing.isNotEmpty()) append(" Falta configurar: ${missing.joinToString(", ")}.")
            if (hub.permissionMissing()) append(" Falta aceptar el permiso USB: tocá «Pedir permiso USB».")
        }
        state.value = TotemState(
            heartRate = display(vitals.heartRate),
            respRate = display(vitals.respRate),
            spo2Pulse = display(vitals.spo2Pulse),
            temperature = if (seen || thermo.latest() != null) temperature else "—",
            nibp = display(vitals.nibp),
            berryUp = berryUp,
            lastData = lastData,
            pusherUp = pusherUp,
            others = others,
            lastPost = lastPost,
            notification = notification,
        )
        onNotification(notification)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val TAG = "BerryMed"
    }
}
