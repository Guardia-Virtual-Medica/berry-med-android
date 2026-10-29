// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.guardiavirtual.berrymed.audio.StethoscopeLink
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.ConfigStore
import com.guardiavirtual.berrymed.net.MetricsPoster
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.otoscope.OtoscopeProbe
import com.guardiavirtual.berrymed.net.PostOutcome
import com.guardiavirtual.berrymed.net.SessionPusher
import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.SampleWindow
import com.guardiavirtual.berrymed.protocol.ThermoReading
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.protocol.applyThermometer
import com.guardiavirtual.berrymed.protocol.metricsEnvelope
import com.guardiavirtual.berrymed.protocol.sendableWindow
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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

    /** Paquetes de la consulta en curso que la cola llena tiró sin mandar. */
    private val droppedPackets = AtomicLong()

    /**
     * Los POST salen de dos hilos aparte: el ciclo de envío no espera la
     * respuesta, así cada paquete lleva un segundo aunque el servidor tarde más.
     * Si la red se traba, quedan a lo sumo 10 paquetes y se descartan los más
     * viejos (un segundo de ECG que llega tarde ya no le sirve al médico, que
     * mira en vivo). Cada descarte queda en la consola y en el último envío:
     * antes se tiraban sin dejar rastro y el hueco no se explicaba.
     */
    private val sender = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(10),
        { runnable -> Thread(runnable, "berry-send").apply { isDaemon = true } },
        RejectedExecutionHandler { runnable, executor ->
            // Lo mismo que DiscardOldestPolicy, contando.
            if (executor.isShutdown) return@RejectedExecutionHandler
            if (executor.queue.poll() != null) {
                droppedPackets.incrementAndGet()
                log("[POST] Se descartó un paquete: la cola estaba llena")
                lastPost = withDropped("La red está lenta: se llenó la cola de envío")
            }
            executor.execute(runnable)
        },
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

    /**
     * Lo que hace el Panel Local con el estetoscopio y el YKUSH3: el hub
     * bloquea hasta un segundo por orden y después se espera a que Android
     * vea las placas de audio. Un solo hilo, así encender, parar y apagar al
     * cerrar el panel salen en el orden en que se pidieron.
     */
    private val stethoWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-panel-stetho").apply { isDaemon = true }
    }
    private val stethoPorts = PanelPorts()

    /**
     * Cuándo llegó la última presión, en hora de la tablet, para que el Panel
     * Local diga "10:07 · hace 4 min". La plaqueta repite el resultado en cada
     * trama de presión: la hora se toma sólo cuando el par cambia o es el
     * primero después de pedir una medición. 0 si no hay.
     */
    @Volatile private var nibpResultAtMillis = 0L
    @Volatile private var nibpResultSeen: Pair<Int, Int>? = null

    /** Cambia con cada Parar y cada cierre del panel: una espera de Escuchar que quedó atrás se abandona. */
    @Volatile private var stethoEpoch = 0L
    @Volatile private var stethoStarting = false

    /**
     * Lo mismo para el otoscopio: el YKUSH3, la espera de la cámara y, en el
     * camino UVC, abrir y cerrar la cámara. Al ser un solo hilo, el puerto se
     * apaga siempre después de soltar la cámara.
     */
    private val otoscopeWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-panel-oto").apply { isDaemon = true }
    }
    private val otoscope = OtoscopePower(OTOSCOPE_WAIT_NANOS, CAMERA2_GRACE_NANOS)
    val otoscopeProbe = OtoscopeProbe(appContext, hub)

    /**
     * El puerto del otoscopio, si lo encendió el panel: sólo ése se apaga. Al
     * encender la tablet el hub arranca con los tres prendidos y la
     * teleconsulta usa el otoscopio por ahí; apagarlo al salir lo dejaría
     * desenchufado para Chrome. Guarda el número, así se apaga el puerto que
     * se encendió aunque la configuración cambie mientras tanto.
     */
    private val otoscopePorts = PanelPorts()

    val otoscopeState: OtoscopePower.State get() = otoscope.state

    /** Donde la cámara UVC del otoscopio se abre y se cierra, en orden con el puerto. */
    val otoscopeExecutor: Executor get() = otoscopeWorker

    private val watch = SessionWatch()
    private val sessionLock = Any()

    /**
     * Consulta o Panel Local, nunca los dos. Se decide siempre dentro de
     * [sessionLock], junto con lo demás que cambia al abrir o cortar.
     */
    private val gate = SessionGate()

    @Volatile var config: BerryConfig = BerryConfig()
    val sessionActive: Boolean get() = gate.sessionActive
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

    /** El estetoscopio del Panel Local; sólo suena con el panel abierto. */
    val stetho = StethoscopeLink(appContext, live.stetho, ::log)

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
            if (berry.startNibp()) resetNibp()
        }
        override fun onConnection(connected: Boolean) {
            pusherUp = connected
        }
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        parser.onNibp = { status, _, sys, _, dia ->
            berry.onNibpStatus(status)
            noteNibpResult(sys, dia)
        }
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
        val (active, epoch, taken) = synchronized(sessionLock) {
            Triple(gate.sessionActive, gate.epoch, parser.takePayload())
        }
        lastWindow = taken
        if (!active && (testing || localMode || !watch.probeDue(now))) return
        val cfg = config
        val url = cfg.metricsUrl() ?: return
        if (!cfg.postReady()) return
        // El termómetro va antes de validar: su lectura sola también se manda.
        val window = sendableWindow(taken, thermo.latest()) ?: return
        if (!active) watch.probed(now)
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
        lastPost = withDropped(describe(outcome, active))
        // Si mientras viajaba el POST llegó un inicio o un fin, la respuesta ya no cuenta.
        if (!active) {
            if (outcome == PostOutcome.Ok) resumeSession(epoch)
            return
        }
        if (!watch.sent(outcome, epoch, System.nanoTime())) return
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

    /** Si la cola tiró paquetes en esta consulta, el último envío lo dice. */
    private fun withDropped(text: String): String {
        val dropped = droppedPackets.get()
        if (dropped == 0L) return text
        return "$text · $dropped ${if (dropped == 1L) "paquete descartado" else "paquetes descartados"} por cola llena"
    }

    private fun startSession() {
        // Se mira dentro del lock, igual que en startLocal: nunca quedan abiertos los dos.
        val opened = synchronized(sessionLock) {
            if (!gate.startSession()) return@synchronized false
            berry.stopNibp()
            parser.resetData()
            thermo.resetLatest()
            droppedPackets.set(0)
            // En el mismo paso que el epoch: los 404 de la consulta anterior ya no cuentan.
            watch.started(gate.epoch, System.nanoTime())
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
            droppedPackets.set(0)
            watch.started(gate.epoch, System.nanoTime())
        }
        log("[SESIÓN] Envío iniciado: la plataforma tenía la toma abierta")
        publish()
    }

    /**
     * La consulta termina sólo por el stop-monitoring o por tres 404 seguidos
     * (la plataforma cerró la toma). No hay tope de minutos: cortaba unos
     * 10 s de datos y la prueba siguiente la reabría igual.
     */
    private fun stopSession(reason: String, byPlatform: Boolean = false, stillDue: () -> Boolean = { true }) {
        synchronized(sessionLock) {
            if (!stillDue()) return
            gate.stopSession()
            berry.stopNibp()
            parser.resetData()
            thermo.resetLatest()
            watch.stopped(System.nanoTime(), byPlatform)
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
        resetNibp()
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

    /** Corta el modo local en el acto; el STOP de la presión y el YKUSH3 salen en otros hilos. */
    fun stopLocal() {
        if (!gate.closeLocal()) return
        // Una presión pedida desde el panel no queda inflando el manguito.
        stopNibpInBackground()
        stopStethoscope(closing = true)
        // La pantalla ya soltó la cámara del otoscopio; acá se apaga su puerto.
        localOtoscopeOff()
        log("[LOCAL] Panel Local cerrado")
        publish()
    }

    fun localNibpStart(): String {
        if (!localMode) return "El panel no está activo."
        if (!berry.startNibp()) return "No se pudo iniciar: plaqueta sin conectar o ya está midiendo."
        resetNibp()
        return "Midiendo…"
    }

    /** Una medición nueva: la presión anterior se borra y su hora también. */
    private fun resetNibp() {
        parser.resetNibp()
        nibpResultSeen = null
        nibpResultAtMillis = 0L
    }

    /** En el hilo del USB, con cada trama de presión. */
    private fun noteNibpResult(sys: Int, dia: Int) {
        if (sys == 0 && dia == 0) return
        val pair = sys to dia
        if (pair == nibpResultSeen) return
        nibpResultSeen = pair
        nibpResultAtMillis = System.currentTimeMillis()
    }

    fun localNibpStop(): String {
        if (!localMode) return "El panel no está activo."
        berry.stopNibp()
        return "Medición detenida"
    }

    /**
     * Escuchar: enciende en el YKUSH3 los puertos del adaptador y del
     * auricular que estén apagados y, si encendió alguno, espera hasta 5 s a
     * que Android vea las dos placas de audio antes de abrir el audio. Si no
     * encendió nada, abre con lo que hay. Sin YKUSH3 se intenta directo.
     * Todo en otro hilo; [done] se llama desde ese hilo con el mensaje para la
     * tarjeta.
     */
    fun localStethoStart(done: (String) -> Unit) {
        if (!localMode) {
            done("El panel no está activo.")
            return
        }
        if (stetho.running || stethoStarting) {
            done(if (stethoStarting) "Encendiendo…" else "Ya está escuchando.")
            return
        }
        stethoStarting = true
        val epoch = stethoEpoch
        stethoWorker.execute {
            try {
                done(startStethoscope(epoch))
            } catch (error: Exception) {
                log("[ESTETO] Falló al empezar: $error")
                done("No se pudo empezar: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                stethoStarting = false
            }
        }
    }

    private fun startStethoscope(epoch: Long): String {
        val cfg = config
        val notes = ArrayList<String>()
        val hubPresent = ykush.plugged()
        var poweredSome = false
        if (hubPresent) {
            val ports = listOf(cfg.ykushStethoscopePort, cfg.ykushHeadsetPort)
            val states = ykush.readAll()
            for (port in stethoPorts.toTurnOn(ports, states)) {
                if (stethoEpoch != epoch) return ""
                if (ykush.setPort(port, true)) {
                    // Sólo si se leyó apagado se apaga al salir; si no se pudo leer, queda encendido.
                    if (!stethoPorts.claim(port, states.getOrNull(port - 1))) {
                        log("[ESTETO] No se pudo leer el puerto $port antes de encenderlo: queda encendido al salir")
                    }
                    poweredSome = true
                } else {
                    notes += "El YKUSH3 no encendió el puerto $port."
                }
            }
        } else {
            notes += "Sin YKUSH3: se intenta directo."
        }
        var found = stetho.devices(cfg)
        // Encender un puerto es enchufar el equipo: Android tarda un momento en ver
        // la placa de audio. Si no se encendió nada, lo que no está no va a aparecer
        // solo; sin YKUSH3 sí se le da tiempo al adaptador, que pudo recién enchufarse.
        val wait = poweredSome || (!hubPresent && found.input == null)
        val deadline = System.nanoTime() + if (wait) STETHO_WAIT_NANOS else 0L
        while ((found.input == null || found.output == null) && System.nanoTime() < deadline) {
            if (stethoEpoch != epoch || !localMode) return ""
            sleep(DEVICE_POLL_MS)
            found = stetho.devices(cfg)
        }
        // Parar o cerrar el panel durante la espera: no se abre nada.
        if (stethoEpoch != epoch || !localMode) return ""
        val reason = stetho.start(cfg)
        if (reason != null) return (notes + reason).joinToString(" ")
        if (!stetho.playing) notes += "Sin auricular: sólo se ve la onda."
        return (listOf("Escuchando.") + notes).joinToString(" ")
    }

    /**
     * Parar: deja de escuchar; los puertos del YKUSH3 quedan como están. Si
     * estaba grabando, la grabación se guarda con lo que tenga (o se descarta
     * si no llegó a 1 s).
     */
    fun localStethoStop(done: (String) -> Unit) {
        stopStethoscope(closing = false)
        stethoWorker.execute { done("Detenido.") }
    }

    /** Grabar 15 s de lo que se está escuchando, en memoria. */
    fun localStethoRecord(): String =
        if (stetho.startRecording()) "Grabando." else "Para grabar hay que estar escuchando."

    /** Corta la grabación en curso y la guarda si pasó de 1 s. */
    fun localStethoStopRecording(): String =
        if (stetho.stopRecording() != null) "Grabación guardada." else "Grabación muy corta: se descartó."

    /**
     * Reproduce una grabación por el auricular. Si se está escuchando, primero
     * se para la escucha (y lo que se estuviera grabando se guarda): las dos
     * no se superponen y el adaptador no queda abierto mientras suena la
     * grabación. En el hilo del estetoscopio, después de parar; [done] se
     * llama desde ahí con el mensaje para el panel.
     */
    fun localStethoPlay(number: Int, done: (String) -> Unit) {
        if (!localMode) {
            done("El panel no está activo.")
            return
        }
        if (stetho.running || stethoStarting) stopStethoscope(closing = false)
        stethoWorker.execute {
            if (!localMode) return@execute
            done(stetho.play(number, config) ?: "Reproduciendo la grabación $number.")
        }
    }

    fun localStethoStopPlayback(done: (String) -> Unit) {
        stethoWorker.execute {
            stetho.stopPlayback()
            done("")
        }
    }

    /** Al bloquear el panel: las grabaciones se descartan, igual que las capturas. */
    fun localStethoDiscard() {
        stethoWorker.execute { stetho.clearRecordings() }
    }

    /**
     * Corta el audio en el hilo del estetoscopio y, al cerrar el panel,
     * descarta las grabaciones y apaga los puertos que el panel encendió.
     * Cambiar el epoch primero hace que una espera de Escuchar en curso se
     * abandone enseguida.
     */
    private fun stopStethoscope(closing: Boolean) {
        stethoEpoch++
        stethoWorker.execute {
            stetho.stop()
            if (!closing) return@execute
            // Después de stop(), que guarda lo que se estaba grabando: así no sobrevive nada.
            stetho.clearRecordings()
            val ports = stethoPorts.takeToTurnOff()
            if (ports.isEmpty() || !ykush.plugged()) return@execute
            for (port in ports) ykush.setPort(port, false)
            log("[ESTETO] Panel cerrado: se apagaron los puertos ${ports.joinToString(", ")} del YKUSH3")
        }
    }

    /**
     * Encender el otoscopio: prende su puerto en el YKUSH3 (y con él la luz)
     * si estaba apagado, espera hasta 8 s a que Android lo vea como cámara
     * externa o como dispositivo USB con permiso, y avisa por cuál camino
     * abrirlo. Si no aparece, apaga el puerto si lo encendió el panel. Todo en el hilo del otoscopio; [progress] y
     * [done] se llaman desde ahí.
     */
    fun localOtoscopeOn(progress: (String) -> Unit, done: (OtoscopePower.Path?, String) -> Unit) {
        if (!localMode) {
            done(null, "El panel no está activo.")
            return
        }
        val hubPresent = ykush.plugged()
        val actions = otoscope.switchOn(hubPresent, System.nanoTime())
        if (actions == null) {
            done(null, "El otoscopio se está encendiendo o apagando: esperá un momento.")
            return
        }
        val port = config.ykushOtoscopePort
        otoscopeWorker.execute {
            try {
                waitForOtoscope(actions, port, progress, done)
            } catch (error: Exception) {
                log("[OTOSCOPIO] Falló al encender: $error")
                // Lo que haya quedado encendido se apaga como siempre.
                localOtoscopeOff()
                done(null, "No se pudo encender: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun waitForOtoscope(
        actions: List<OtoscopePower.Action>,
        port: Int,
        progress: (String) -> Unit,
        done: (OtoscopePower.Path?, String) -> Unit,
    ) {
        if (OtoscopePower.Action.PORT_ON in actions) {
            progress("Encendiendo…")
            // Un estado que no se pudo leer se enciende igual, pero no se apaga al salir:
            // pudo estar encendido desde el arranque, para la teleconsulta.
            val states = ykush.readAll()
            if (otoscopePorts.toTurnOn(listOf(port), states).isEmpty()) {
                log("[OTOSCOPIO] El puerto $port ya está encendido")
            } else if (ykush.setPort(port, true)) {
                if (!otoscopePorts.claim(port, states.getOrNull(port - 1))) {
                    log("[OTOSCOPIO] No se pudo leer el puerto $port antes de encenderlo: queda encendido al salir")
                }
            } else {
                log("[OTOSCOPIO] El YKUSH3 no encendió el puerto $port: se espera igual")
            }
            if (!otoscope.portReady(System.nanoTime())) return
            progress("Esperando la cámara…")
        } else {
            progress("Sin YKUSH3: se intenta directo. Esperando la cámara…")
        }
        while (true) {
            when (val poll = otoscope.poll(System.nanoTime(), otoscopeProbe.cameraSeen(), otoscopeProbe.usbReady())) {
                OtoscopePower.Poll.Waiting -> sleep(DEVICE_POLL_MS)
                OtoscopePower.Poll.Cancelled -> return
                is OtoscopePower.Poll.Open -> {
                    log("[OTOSCOPIO] Apareció: se abre ${if (poll.path == OtoscopePower.Path.CAMERA2) "por Camera2" else "por UVC"}")
                    done(poll.path, "Abriendo la imagen…")
                    return
                }
                is OtoscopePower.Poll.TimedOut -> {
                    if (otoscopeProbe.usbPresent() && !otoscopeProbe.usbReady()) {
                        // Enchufado pero sin permiso: el pedido sale una vez por dispositivo y
                        // alguien lo cerró. Se pide de nuevo y el puerto queda encendido, porque
                        // apagarlo desenchufa el otoscopio y el pedido no serviría. Si lo
                        // encendió el panel, se apaga al salir.
                        log("[OTOSCOPIO] Está enchufado pero sin permiso USB: se vuelve a pedir")
                        hub.forgetAsked()
                        done(
                            null,
                            "Falta aceptar el permiso USB del otoscopio: aceptá el pedido que aparece " +
                                "o, si no aparece, tocá «Pedir permiso USB» en Berry Med. Después, Encender otra vez.",
                        )
                        return
                    }
                    if (OtoscopePower.Action.PORT_OFF in poll.actions) turnOffOtoscopePort()
                    log("[OTOSCOPIO] No apareció en ${OTOSCOPE_WAIT_NANOS / 1_000_000_000L} s")
                    done(null, "El otoscopio no aparece como cámara.")
                    return
                }
            }
        }
    }

    /**
     * Apagar el otoscopio, también al cerrar el panel o si la imagen no
     * abrió. La pantalla suelta la cámara antes de llamar; acá queda apagar
     * el puerto, en el hilo del otoscopio y después de cualquier cierre UVC
     * que esté en la cola. Sólo si lo encendió el panel: si ya estaba
     * encendido, queda como estaba.
     *
     * Aunque el otoscopio ya esté apagado se pasa por el hilo: un puerto que
     * quedó encendido esperando el permiso USB también se apaga al salir.
     */
    fun localOtoscopeOff() {
        val powering = OtoscopePower.Action.PORT_OFF in otoscope.switchOff()
        otoscopeWorker.execute {
            try {
                turnOffOtoscopePort()
            } finally {
                if (powering) otoscope.poweredOff()
            }
        }
    }

    /** Apaga el puerto del otoscopio si lo encendió el panel, y lo olvida. En el hilo del otoscopio. */
    private fun turnOffOtoscopePort() {
        val ports = otoscopePorts.takeToTurnOff()
        if (ports.isEmpty() || !ykush.plugged()) return
        for (port in ports) ykush.setPort(port, false)
        log("[OTOSCOPIO] Se apagó el puerto ${ports.joinToString(", ")} del YKUSH3, que había encendido el panel")
    }

    fun localSnapshot(): LocalSnapshot {
        val stats = parser.stats()
        val reading = thermo.latest()
        val thermoAt = thermo.lastReadingAtNano
        val plugged = hub.panelDevices()
        return LocalSnapshot(
            berryUp = berry.connected,
            lastDataAge = berry.lastDataAgeSeconds(),
            vitals = stats.vitals,
            ecgInfo = stats.ecgInfo,
            thermoConnected = thermo.connected,
            thermoReading = reading?.let { r -> formatThermo(r) },
            // El termómetro guarda el instante en nanoTime; la pantalla quiere la hora del reloj.
            thermoAtMillis = if (thermoAt == 0L) null else System.currentTimeMillis() - (System.nanoTime() - thermoAt) / 1_000_000L,
            nibpRunning = berry.nibpRunning,
            nibp = live.nibp,
            nibpAtMillis = nibpResultAtMillis.takeIf { it != 0L },
            stethoPlugged = plugged.stethoAdapter,
            stethoAvailable = stetho.running || plugged.ykush || plugged.stethoAdapter,
            stethoStarting = stethoStarting,
            stethoRunning = stetho.running,
            stethoPlaying = stetho.playing,
            stethoFilter = stetho.filter,
            stethoRecordingSeconds = stetho.recording,
            stethoRecordings = stetho.recordings(),
            stethoReplaying = stetho.replaying,
            otoscopePlugged = plugged.otoscope,
            otoscopeAvailable = otoscope.state != OtoscopePower.State.OFF || plugged.ykush || plugged.otoscope,
            otoscopeState = otoscope.state,
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
        // Lo mismo que saldría: con el termómetro aplicado antes de validar.
        val preview = lastWindow?.let { window -> sendableWindow(window, reading) }?.let { ready ->
            metricsEnvelope(System.currentTimeMillis(), splitWindow(ready).last())
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

    /**
     * Al logcat y a la consola de Testing. Lo usan también las pantallas para
     * lo que pasa en ellas y conviene ver ahí, como una imagen que no abrió.
     */
    internal fun log(message: String) {
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
        private const val STETHO_WAIT_NANOS = 5L * 1_000_000_000L
        private const val DEVICE_POLL_MS = 250L
        private const val OTOSCOPE_WAIT_NANOS = 8L * 1_000_000_000L

        /** Visto el USB del otoscopio, cuánto se espera a que la tablet lo liste como cámara antes de ir por UVC. */
        private const val CAMERA2_GRACE_NANOS = 2L * 1_000_000_000L
    }
}
