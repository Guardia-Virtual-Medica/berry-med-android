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
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.SampleWindow
import com.guardiavirtual.berrymed.protocol.ThermoReading
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.protocol.applyThermometer
import com.guardiavirtual.berrymed.protocol.isValidSample
import com.guardiavirtual.berrymed.protocol.metricsEnvelope
import com.guardiavirtual.berrymed.protocol.sessionExpired
import com.guardiavirtual.berrymed.protocol.splitWindow
import com.guardiavirtual.berrymed.protocol.summarize
import com.guardiavirtual.berrymed.usb.BerryLink
import com.guardiavirtual.berrymed.usb.DeviceInventory
import com.guardiavirtual.berrymed.usb.ThermometerLink
import com.guardiavirtual.berrymed.usb.UsbHub
import com.guardiavirtual.berrymed.usb.YkushHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TotemRuntime(context: Context) {
    private val appContext = context.applicationContext
    val state = MutableStateFlow(TotemState())
    val store = ConfigStore(appContext)
    private val parser = BmDataParser()
    private val hub = UsbHub(appContext)
    private val ykush = YkushHub(hub, ::log)
    private val inventory = DeviceInventory(appContext)
    private val berry = BerryLink(hub, parser, { config.tuning() }, ::log)
    private val thermo = ThermometerLink(hub, { berry.connected }, ::log)
    private val poster = MetricsPoster()

    /**
     * Los POST salen de dos hilos aparte: el ciclo de envío no espera la
     * respuesta, así cada paquete lleva un segundo aunque el servidor tarde más.
     * Si la red se traba, quedan a lo sumo 10 paquetes y se descartan los más viejos.
     */
    private val sender = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(10),
        { runnable -> Thread(runnable, "berry-send").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    /**
     * El STOP de la presión al cerrar el Panel Local o Testing. Escribir en el
     * puerto serie puede tardar hasta un segundo y esos cierres llegan desde
     * la pantalla: si se escribiera ahí, cerrar con el manguito inflando
     * trabaría la tablet. El hilo es del servicio y no de la pantalla, así el
     * STOP sale aunque la actividad se destruya enseguida.
     */
    private val nibpStopper = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-nibp-stop").apply { isDaemon = true }
    }
    private val watch = SessionWatch()
    private val sessionLock = Any()

    /**
     * Consulta o Panel Local, nunca los dos. Se decide siempre dentro de
     * [sessionLock], junto con lo demás que cambia al abrir o cortar.
     */
    private val gate = SessionGate()

    @Volatile var config: BerryConfig = BerryConfig()
    val sessionActive: Boolean get() = gate.sessionActive
    @Volatile private var sessionStartNano: Long = 0L
    @Volatile var lastPost: String = "Todavía no se envió"
    @Volatile var pusherUp: Boolean = false

    /** Con el panel Testing abierto no sale ningún POST de prueba: sólo se mira. */
    @Volatile var testing: Boolean = false
        private set
    /**
     * Con el Panel Local abierto no sale ningún POST ni se atiende ninguna
     * orden de Pusher: la tablet está atendiendo a alguien de forma presencial.
     */
    val localMode: Boolean get() = gate.localMode

    /** Las muestras en vivo para el Panel Local; el parser las copia acá apenas las decodifica. */
    val live = LiveFeed()
    @Volatile private var lastWindow: SampleWindow? = null
    @Volatile private var inventoryCache: DeviceInventory.Snapshot? = null
    val console = ConsoleLog()
    private val testingSession = TestingSession()

    var onNotification: (text: String) -> Unit = {}

    private val pusher = SessionPusher(::log, object : SessionPusher.Listener {
        override fun onStart() = startSession()

        override fun onStop() {
            if (localMode) {
                log("[SESIÓN] Llegó un fin de consulta con el Panel Local abierto: se ignora")
                return
            }
            stopSession("evento stop", byPlatform = true)
        }

        override fun onBloodPressure() {
            // Con el Panel Local abierto la presión la pide quien está al lado del paciente.
            if (localMode) {
                log("[SESIÓN] Llegó un pedido de presión con el Panel Local abierto: se ignora")
                return
            }
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
        parser.live = live
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

    /**
     * Un ciclo por segundo a ritmo fijo, sin esperar las respuestas. Antes se
     * esperaba la respuesta y después un segundo más: con el servidor tardando,
     * cada paquete juntaba dos segundos o más, pasaba los 10 KB que acepta
     * Pusher y el médico no recibía nada aunque el servidor contestara 200.
     */
    private fun postLoop() {
        var next = System.nanoTime()
        while (true) {
            try {
                stopSessionIfExpired()
                sendOnce()
            } catch (error: Exception) {
                log("[DATA] Error en el envío: $error")
                lastPost = "Error al enviar: ${error.message ?: error.javaClass.simpleName}"
            }
            next += TICK_NANOS
            val waitMs = (next - System.nanoTime()) / 1_000_000L
            if (waitMs > 0) {
                sleep(waitMs)
            } else {
                // Si el ciclo se atrasó, sigue desde ahora: no se recupera en ráfaga.
                next = System.nanoTime()
            }
        }
    }

    /**
     * Con sesión manda cada segundo. Sin sesión las ondas se descartan cada
     * segundo, y cada 10 s se prueba con el último: si la plataforma tiene una
     * toma abierta, la tablet se perdió el start-monitoring y empieza a enviar.
     */
    private fun sendOnce() {
        val now = System.nanoTime()
        val (active, epoch, window) = synchronized(sessionLock) {
            Triple(gate.sessionActive, gate.epoch, parser.takePayload())
        }
        lastWindow = window
        if (!active && (testing || localMode || !watch.probeDue(now))) return
        val cfg = config
        val url = cfg.metricsUrl() ?: return
        if (!cfg.postReady() || !isValidSample(window)) return
        if (!active) watch.probed(now)
        applyThermometer(window.vitalSigns, thermo.latest())
        // Tramos de a lo sumo un segundo, por el límite de Pusher. La prueba lleva sólo el último.
        val parts = splitWindow(window)
        val toSend = if (active) parts else listOf(parts.last())
        val sentAt = System.currentTimeMillis()
        toSend.forEachIndexed { index, part ->
            val body = metricsEnvelope(sentAt - (toSend.size - 1 - index) * 1_000L, part)
            log("[DATA] ${if (active) "" else "prueba "}${summarize(part)} (${body.length} B)")
            sender.execute { deliver(url, cfg.apiUsername, cfg.apiPassword, body, active, epoch) }
        }
    }

    private fun deliver(url: String, username: String, password: String, body: String, active: Boolean, epoch: Long) {
        val outcome = try {
            poster.post(url, username, password, body)
        } catch (error: Exception) {
            PostOutcome.Failed(error.message ?: error.javaClass.simpleName)
        }
        lastPost = describe(outcome, active)
        // Si mientras viajaba el POST llegó un inicio o un fin, la respuesta ya no cuenta.
        if (!active) {
            if (outcome == PostOutcome.Ok) resumeSession(epoch)
            return
        }
        if (!watch.sent(outcome)) return
        stopSession("la plataforma no tiene la toma abierta") { gate.epoch == epoch }
    }

    private fun describe(outcome: PostOutcome, active: Boolean): String = when {
        outcome == PostOutcome.Ok -> "Último envío correcto"
        outcome is PostOutcome.Http && outcome.code == 404 && !active -> "Esperando que se abra una toma de signos"
        outcome is PostOutcome.Http && outcome.code == 404 -> "La plataforma no tiene la toma abierta (404)"
        outcome is PostOutcome.Http -> "La API respondió ${outcome.code}"
        outcome is PostOutcome.Failed -> "No se pudo enviar: ${outcome.message}"
        else -> "No se pudo enviar"
    }

    private fun startSession() {
        // Se mira dentro del lock, igual que en startLocal: nunca quedan abiertos los dos.
        val opened = synchronized(sessionLock) {
            if (!gate.startSession()) return@synchronized false
            berry.stopNibp()
            parser.resetData()
            thermo.resetLatest()
            sessionStartNano = System.nanoTime()
            true
        }
        if (!opened) {
            log("[SESIÓN] Llegó un inicio de consulta con el Panel Local abierto: se ignora")
            return
        }
        if (testing) {
            // La consulta gana: Testing no puede dejar a un paciente sin envío.
            endTesting()
            log("[TEST] Llegó el inicio de una consulta: se corta la prueba")
        }
        watch.started()
        log("[SESIÓN] Envío iniciado")
        publish()
    }

    /**
     * La plataforma ya tenía la toma abierta y la prueba fue aceptada. No se
     * limpia nada: lo que se acaba de mandar ya es parte de la toma.
     */
    private fun resumeSession(epoch: Long) {
        synchronized(sessionLock) {
            // Un POST de prueba que salió justo antes de abrir el Panel Local no arranca la consulta.
            if (!gate.resumeSession(epoch)) return
            sessionStartNano = System.nanoTime()
        }
        watch.started()
        log("[SESIÓN] Envío iniciado: la plataforma tenía la toma abierta")
        publish()
    }

    /** Se mira dentro del lock: un inicio que llega justo antes no se corta. */
    private fun stopSessionIfExpired() {
        val minutes = config.maxSessionMinutes
        stopSession("sin stop tras $minutes min") {
            sessionExpired(sessionActive, sessionStartNano, System.nanoTime(), minutes)
        }
    }

    private fun stopSession(reason: String, byPlatform: Boolean = false, stillDue: () -> Boolean = { true }) {
        synchronized(sessionLock) {
            if (!stillDue()) return
            gate.stopSession()
            sessionStartNano = 0L
            berry.stopNibp()
            parser.resetData()
            thermo.resetLatest()
        }
        watch.stopped(System.nanoTime(), byPlatform)
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
            localMode -> "Panel Local abierto"
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
            append(if (ykush.plugged()) "Hub YKUSH3 presente. " else "Hub YKUSH3 sin conectar. ")
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

    /** Devuelve el motivo si no se puede probar ahora. */
    fun startTesting(): String? {
        if (sessionActive) return "Hay una consulta enviando: la prueba espera a que termine."
        // Al revés no: abrir el panel sí corta la prueba. Testing es diagnóstico y el
        // panel está atendiendo a un paciente; en pantalla dividida no se le saca la presión.
        if (localMode) return "El Panel Local está abierto: la prueba espera a que se cierre."
        testingSession.reset()
        testing = true
        log("[TEST] Prueba iniciada: no se envía nada a la API")
        return null
    }

    fun stopTesting() {
        if (!testing) return
        endTesting()
        log("[TEST] Prueba detenida")
    }

    private fun endTesting() {
        testing = false
        testingSession.reset()
        // Una presión pedida desde Testing no queda inflando el manguito.
        stopNibpInBackground()
    }

    /**
     * Manda STOP fuera del hilo que llama, que suele ser el de la pantalla.
     * Se decide ahora: una presión de una consulta que arranca después no se toca.
     */
    private fun stopNibpInBackground() {
        if (!berry.nibpRunning || sessionActive) return
        nibpStopper.execute { berry.stopNibp() }
    }

    fun checkMeasure(measure: TestingSession.Measure) {
        testingSession.check(measure, parser.stats())
    }

    fun testNibpStart(): String {
        if (!testing) return "Iniciá la prueba primero."
        if (sessionActive) return "Hay una consulta enviando."
        if (!berry.startNibp()) return "No se pudo iniciar: plaqueta sin conectar o ya está midiendo."
        parser.resetNibp()
        return "Midiendo…"
    }

    fun testNibpStop(): String {
        // Una presión pedida por el médico no se para desde Testing.
        if (sessionActive) return "Hay una consulta enviando."
        berry.stopNibp()
        return "STOP enviado"
    }

    /** Devuelve el motivo si el Panel Local no puede abrirse ahora. */
    fun startLocal(): String? {
        // Dentro del lock: una prueba aceptada no puede arrancar la consulta entre el chequeo y el cambio.
        synchronized(sessionLock) {
            if (!gate.openLocal()) return "Hay una consulta enviando: el Panel Local espera a que termine."
        }
        // El panel arranca en blanco: ni ondas ni el estado de una presión de antes.
        live.clear()
        if (testing) {
            endTesting()
            log("[TEST] Se abrió el Panel Local: se corta la prueba")
        }
        log("[LOCAL] Panel Local abierto: no se envía nada a la API ni se atienden órdenes")
        publish()
        return null
    }

    /** Corta el modo local en el acto; el STOP de la presión sale en otro hilo. */
    fun stopLocal() {
        if (!gate.closeLocal()) return
        // Una presión pedida desde el panel no queda inflando el manguito.
        stopNibpInBackground()
        log("[LOCAL] Panel Local cerrado")
        publish()
    }

    fun localNibpStart(): String {
        if (!localMode) return "El panel no está activo."
        if (!berry.startNibp()) return "No se pudo iniciar: plaqueta sin conectar o ya está midiendo."
        parser.resetNibp()
        return "Midiendo…"
    }

    fun localNibpStop(): String {
        if (!localMode) return "El panel no está activo."
        berry.stopNibp()
        return "Medición detenida"
    }

    fun localSnapshot(): LocalSnapshot {
        val stats = parser.stats()
        val reading = thermo.latest()
        val thermoAt = thermo.lastReadingAtNano
        return LocalSnapshot(
            berryUp = berry.connected,
            lastDataAge = berry.lastDataAgeSeconds(),
            vitals = stats.vitals,
            ecgInfo = stats.ecgInfo,
            thermoConnected = thermo.connected,
            thermoReading = reading?.let { r -> formatThermo(r) },
            thermoAge = if (thermoAt == 0L) null else (System.nanoTime() - thermoAt) / 1_000_000_000L,
            nibpRunning = berry.nibpRunning,
            nibp = live.nibp,
        )
    }

    /** Lee los tres puertos del YKUSH3 sin cambiarlos. Bloquea hasta un segundo: llamarlo fuera del hilo de la pantalla. */
    fun testYkushRead(): String {
        if (!ykush.plugged()) return "No hay hub YKUSH3 conectado."
        if (hub.permissionMissing() && ykush.lastStates.all { it == null }) hub.forgetAsked()
        val states = ykush.readAll()
        if (states.all { it == null }) return "El hub no contesta. Si recién lo enchufaste, aceptá el permiso USB."
        return "Estado leído"
    }

    /**
     * Enciende o apaga un puerto del YKUSH3 desde Testing. Con una consulta
     * enviando no se toca nada: podría dejar al médico sin un equipo.
     * Bloquea hasta un segundo: llamarlo fuera del hilo de la pantalla.
     */
    fun testYkushSet(port: Int, on: Boolean): String = when {
        sessionActive -> "Hay una consulta enviando: no se cambia ningún puerto."
        !ykush.plugged() -> "No hay hub YKUSH3 conectado."
        !ykush.setPort(port, on) -> "El hub no aceptó la orden. Mirá la consola."
        else -> "Puerto $port ${if (on) "encendido" else "apagado"}"
    }

    /** Lo que Android ve enchufado, renovado cada [INVENTORY_NANOS] mientras Testing lo mira. */
    private fun inventorySnapshot(): DeviceInventory.Snapshot {
        val cached = inventoryCache
        val now = System.nanoTime()
        if (cached != null && now - cached.takenAtNano < INVENTORY_NANOS) return cached
        val fresh = inventory.snapshot()
        inventoryCache = fresh
        return fresh
    }

    fun testingSnapshot(): TestingSnapshot {
        val stats = parser.stats()
        val berryUp = berry.connected
        val verdicts = testingSession.poll(stats, berryUp)
        val reading = thermo.latest()
        val thermoAt = thermo.lastReadingAtNano
        val cfg = config
        val window = lastWindow
        val preview = if (window != null && isValidSample(window)) {
            val vitals = window.vitalSigns.copy()
            applyThermometer(vitals, reading)
            metricsEnvelope(System.currentTimeMillis(), splitWindow(window.copy(vitalSigns = vitals)).last())
        } else {
            null
        }
        return TestingSnapshot(
            testing = testing,
            sessionActive = sessionActive,
            berryUp = berryUp,
            berryPort = berry.portName(),
            lastDataAge = berry.lastDataAgeSeconds(),
            rates = testingSession.rates(),
            verdicts = verdicts,
            stats = stats,
            nibpRunning = berry.nibpRunning,
            thermoConnected = thermo.connected,
            thermoPort = thermo.portName,
            thermoReading = reading?.let { "${it.value} ${it.unit}" },
            thermoAge = if (thermoAt == 0L) null else (System.nanoTime() - thermoAt) / 1_000_000_000L,
            thermoLine = thermo.lastLine,
            ykushPlugged = ykush.plugged(),
            ykushPorts = cfg.ykushPortRoles().mapIndexed { index, role ->
                YkushPortView(port = index + 1, role = role, on = ykush.lastStates.getOrNull(index))
            },
            inventory = inventorySnapshot(),
            url = cfg.metricsUrl(),
            preview = preview,
            console = console.snapshot(),
        )
    }

    private fun formatThermo(reading: ThermoReading): String {
        val vitals = VitalSigns()
        applyThermometer(vitals, reading)
        return vitals.temperature
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        console.add(message)
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
        private const val TICK_NANOS = 1_000_000_000L
        private const val INVENTORY_NANOS = 3L * 1_000_000_000L
    }
}
