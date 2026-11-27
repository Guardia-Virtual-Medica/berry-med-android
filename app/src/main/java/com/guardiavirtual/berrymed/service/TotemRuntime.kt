// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.audio.AudioPick
import com.guardiavirtual.berrymed.audio.CallAudioRouter
import com.guardiavirtual.berrymed.audio.StethoDsp
import com.guardiavirtual.berrymed.audio.StethoFilter
import com.guardiavirtual.berrymed.audio.StethoscopeLink
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.ConfigStore
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.i18n.LogText
import com.guardiavirtual.berrymed.i18n.Txt
import com.guardiavirtual.berrymed.i18n.join
import com.guardiavirtual.berrymed.i18n.raw
import com.guardiavirtual.berrymed.i18n.txt
import com.guardiavirtual.berrymed.net.MetricsPoster
import com.guardiavirtual.berrymed.otoscope.OtoscopePermission
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.otoscope.OtoscopeProbe
import com.guardiavirtual.berrymed.net.PostOutcome
import com.guardiavirtual.berrymed.net.SessionPusher
import com.guardiavirtual.berrymed.net.WifiWatch
import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.SampleWindow
import com.guardiavirtual.berrymed.protocol.ThermoReading
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.protocol.applyThermometer
import com.guardiavirtual.berrymed.protocol.metricsEnvelope
import com.guardiavirtual.berrymed.protocol.sendableWindow
import com.guardiavirtual.berrymed.protocol.thermometerForPacket
import com.guardiavirtual.berrymed.protocol.splitWindow
import com.guardiavirtual.berrymed.protocol.summarize
import com.guardiavirtual.berrymed.tele.Instrument
import com.guardiavirtual.berrymed.tele.KioskUrls
import com.guardiavirtual.berrymed.tele.LiveKitBackend
import com.guardiavirtual.berrymed.tele.RoomState
import com.guardiavirtual.berrymed.tele.TeleOrder
import com.guardiavirtual.berrymed.tele.TeleStreamer
import com.guardiavirtual.berrymed.tele.TeleView
import com.guardiavirtual.berrymed.usb.BerryLink
import com.guardiavirtual.berrymed.usb.DeviceInventory
import com.guardiavirtual.berrymed.usb.ThermometerLink
import com.guardiavirtual.berrymed.usb.UsbHub
import com.guardiavirtual.berrymed.usb.YkushHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
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

    /** Los mensajes que entran a una línea de log, siempre en español (ver [logText]). */
    private val logLanguage = LogText(appContext)
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
                lastPost = withDropped(txt(R.string.rt_post_queue_full))
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

    /** Cuándo toca llevar el hub al estado de reposo o al del kiosco; lo hace [watchLoop]. */
    private val hubRest = HubRestState.Pending()

    /**
     * Si el kiosco está visible (entre su `onStart` y su `onStop`): con él
     * adelante el otoscopio y el estetoscopio quedan encendidos. Ver [HubRestState].
     */
    @Volatile private var kioskVisible = false

    /**
     * Hasta leer lo guardado, [config] es la de fábrica: con ésa no se apaga
     * ningún puerto ni se acepta el PIN del Panel Local (vale 1515 aunque haya
     * otro guardado). Las pantallas lo miran con [com.guardiavirtual.berrymed.config.checkPin].
     */
    @Volatile var configLoaded = false
        private set

    /**
     * Cuándo llegó la última presión, en hora de la tablet, para que el Panel
     * Local diga "10:07 · hace 4 min". La plaqueta repite el resultado en cada
     * trama de presión: la hora se toma sólo cuando el par cambia o es el
     * primero después de pedir una medición. 0 si no hay.
     */
    @Volatile private var nibpResultAtMillis = 0L
    @Volatile private var nibpResultSeen: Pair<Int, Int>? = null

    /**
     * Si en esta apertura del Panel Local se pidió una presión con Medir. Hasta
     * entonces el panel no muestra ninguna: la que tenga el parser es de la
     * persona anterior (o de una consulta), y la plaqueta la repite en las
     * tramas de presión, así que borrarla al abrir no alcanza.
     */
    @Volatile private var localNibpAsked = false

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
    val otoscopeProbe = OtoscopeProbe(appContext, hub) { askOtoscopePermission() }

    /** Un ciclo del puerto para el permiso del otoscopio a la vez: tocar el botón varias veces no los encola. */
    private val otoscopePermissionCycling = AtomicBoolean(false)

    /**
     * El puerto del otoscopio, si lo encendió el panel: sólo ése se apaga. Si
     * ya estaba encendido queda como estaba. Guarda el número, así se apaga el
     * puerto que se encendió aunque la configuración cambie mientras tanto.
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
    @Volatile var lastPost: Txt = TotemStatus.NOT_SENT
    @Volatile var pusherUp: Boolean = false

    /** Con el panel Testing abierto no sale ningún POST de prueba: sólo se mira. */
    @Volatile var testing: Boolean = false
        private set

    /**
     * Con la pantalla Testing abierta, aunque la prueba no haya empezado, el
     * reposo no toca el hub (salvo encender el termómetro): un puerto
     * encendido a mano queda encendido mientras se está ahí.
     */
    @Volatile private var testingScreenOpen = false
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

    var onNotification: (text: Txt) -> Unit = {}

    /**
     * Los tipos con los que el servicio está en primer plano (los pone
     * [MonitorService]). Sin cámara o sin micrófono, la teleconsulta no puede
     * capturar ese instrumento con Chrome adelante.
     */
    @Volatile var foregroundTypes: Int = 0

    private val teleBackend = LiveKitBackend(
        context = appContext,
        config = { config },
        ykush = ykush,
        probe = otoscopeProbe,
        stethoInput = { cfg -> stetho.devices(cfg).input },
        // paso 4b: LiveKitBackend todavía recibe el motivo como texto; va en español, como su log.
        blockedBy = { instrument -> teleBlocked(instrument)?.let(::logText) },
        log = ::log,
    )

    /** La sala de LiveKit de la teleconsulta: el otoscopio y el estetoscopio para el médico. */
    val tele = TeleStreamer(teleBackend, ::log).also { streamer ->
        teleBackend.onRoomLost = streamer::roomLost
    }

    /** Fija la salida de la videollamada del `WebView`, que corre en este proceso. */
    private val callAudio = CallAudioRouter(appContext, { config }, ::log)

    /** El Wi-Fi del panel lateral. */
    private val wifi = WifiWatch(appContext)

    /** La red de la tablet, para el aviso «Sin conexión» de la pantalla principal. */
    val wifiState get() = wifi.state
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /**
     * Si el panel lateral está abierto o en el riel. Vive sólo en memoria: se
     * mantiene entre el kiosco y el Panel Local y vuelve al riel (colapsado)
     * al reiniciar el proceso. Se toca desde el hilo principal.
     */
    var statusPanelOpen = false

    private val pusher = SessionPusher(::log, object : SessionPusher.Listener {
        override fun onStart() = startSession()

        override fun onStop() {
            // Llega también al completar los signos, antes de la videollamada: sólo se despublica, la sala sigue.
            tele.unpublishAll("fin de la toma de signos")
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

        override fun onInstrument(order: TeleOrder) = tele.order(order)
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
            // Un error de lectura que no es de E/S no pisa la última configuración buena
            // (antes volvía la de fábrica, con el PIN 1515): queda en la consola y se reintenta.
            store.flow.retryWhen { cause, _ ->
                log("[CONFIG] No se pudo leer la configuración: $cause. Se sigue con la última leída y se reintenta en 5 s")
                delay(CONFIG_RETRY_MS)
                true
            }.collect { saved ->
                val outputChanged = configLoaded && saved.callAudioOutput != config.callAudioOutput
                config = saved
                configLoaded = true
                pusher.apply(saved)
                if (outputChanged) callAudio.refresh()
            }
        }
        callAudio.start()
        wifi.start()
        thread(name = "berry-usb", isDaemon = true) { berry.run() }
        thread(name = "berry-thermo", isDaemon = true) { thermo.run() }
        thread(name = "berry-post", isDaemon = true) { postLoop() }
        thread(name = "berry-watch", isDaemon = true) { watchLoop() }
    }

    /**
     * Lo llama el kiosco al quedar visible y al dejar de estarlo. El hub cambia
     * en la vuelta siguiente de [watchLoop], salvo que alguien esté usando un
     * equipo: entonces se espera. El diálogo del PIN no saca al kiosco de
     * adelante, así que no apaga nada.
     */
    fun setKioskVisible(visible: Boolean) {
        if (kioskVisible == visible) return
        kioskVisible = visible
        log(
            if (visible) "[YKUSH] El kiosco quedó adelante: se buscan los puertos con el kiosco"
            else "[YKUSH] El kiosco dejó de estar adelante: se buscan los puertos en reposo",
        )
        hubRest.again()
    }

    fun requestUsbPermissionAgain() {
        hub.forgetAsked()
        askOtoscopePermission()
    }

    /**
     * La fila de la plaqueta, el hub o el termómetro en el panel lateral:
     * vuelve a pedir lo que falta, en fila, como «Pedir permiso USB» pero sin
     * tocar el otoscopio. Su pedido es el de Android al ciclar el puerto, que
     * saldría a la par del de la fila y uno de los dos se perdería; el
     * otoscopio tiene su propia fila ([askOtoscopePermission]).
     */
    fun requestUsbPermissions() {
        hub.forgetAsked()
    }

    /**
     * Vuelve a pedir el permiso del otoscopio con el pedido de Android que trae
     * «Usar de forma predeterminada»: apaga y enciende su puerto, y Android lo
     * ve como recién conectado. Va por el hilo del otoscopio, en orden con lo
     * que haga el Panel Local, y no toca el estado del kiosco ni [PanelPorts]:
     * el puerto vuelve a quedar encendido, como estaba. Sin hub, el pedido sin
     * casilla. No bloquea.
     */
    fun askOtoscopePermission() {
        val step = otoscopePermissionStep() ?: return
        if (step == OtoscopePermission.Step.REQUEST) {
            val device = otoscopeProbe.usbDevice() ?: return
            log("[USB] Permiso del otoscopio: sin YKUSH3 se pide sin «Usar de forma predeterminada»")
            hub.requestPermission(device)
            return
        }
        if (!otoscopePermissionCycling.compareAndSet(false, true)) return
        otoscopeWorker.execute {
            try {
                cycleOtoscopePort()
            } catch (error: Exception) {
                log("[USB] Falló el ciclo del puerto del otoscopio: $error")
            } finally {
                otoscopePermissionCycling.set(false)
            }
        }
    }

    /** Qué hacer ahora con el permiso del otoscopio, o null si nada. */
    private fun otoscopePermissionStep(): OtoscopePermission.Step? {
        val device = otoscopeProbe.usbDevice()
        val step = OtoscopePermission.next(
            present = device != null,
            granted = device?.let(hub.manager::hasPermission) == true,
            hubUsable = ykush.usableDeviceId() != null,
            port = config.ykushOtoscopePort,
        )
        return step.takeIf { it != OtoscopePermission.Step.NOTHING }
    }

    private fun cycleOtoscopePort() {
        // Se mira justo antes de apagar: con permiso puede estar publicando o abierto en el panel.
        val step = otoscopePermissionStep() ?: return
        val port = config.ykushOtoscopePort
        if (step == OtoscopePermission.Step.CYCLE_PORT) {
            log("[USB] Permiso del otoscopio: se apaga y enciende el puerto $port para que Android lo pida con «Usar de forma predeterminada»")
            if (ykush.setPort(port, false)) {
                sleep(OTOSCOPE_CYCLE_OFF_MS)
                if (!ykush.setPort(port, true)) log("[USB] El YKUSH3 no volvió a encender el puerto $port del otoscopio")
                return
            }
        }
        // El hub no contestó: el pedido de siempre, sin casilla.
        val device = otoscopeProbe.usbDevice() ?: return
        if (hub.manager.hasPermission(device)) return
        log("[USB] Permiso del otoscopio: el YKUSH3 no contesta, se pide sin «Usar de forma predeterminada»")
        hub.requestPermission(device)
    }

    /** La respuesta de un pedido de permiso USB: libera el pedido en vuelo y la vuelta siguiente de [watchLoop] pide el próximo. */
    fun onUsbPermission(device: UsbDevice?, granted: Boolean) {
        hub.permissionAnswered(device?.deviceId)
        if (device != null) {
            log("[USB] Permiso ${if (granted) "aceptado" else "rechazado"} para ${device.deviceName}")
        }
        publish()
    }

    /**
     * Lo que tiene que seguir andando aunque un POST tarde: los pedidos de
     * permiso USB, la reconexión de Pusher y la pantalla. El estado de reposo
     * del hub va acá porque es donde se mira el USB: un hub que recién tiene
     * permiso más tarde entra solo en la vuelta siguiente.
     */
    private fun watchLoop() {
        while (true) {
            guarded("permisos USB") { hub.requestMissingPermissions() }
            guarded("puertos en reposo") { applyHubRest() }
            guarded("Pusher") { pusher.keepAlive() }
            guarded("teleconsulta") { tele.tick() }
            guarded("pantalla") { publish() }
            sleep(1_000)
        }
    }

    /**
     * Lleva los puertos al estado de reposo, o al del kiosco si está adelante,
     * una vez por hub, o de nuevo si se abrió o se dejó el kiosco o se apagó
     * el termómetro desde Testing. Si alguien está usando un equipo, igual
     * enciende el termómetro (encender no le saca nada a nadie) y lo demás
     * espera, sin marcarlo hecho: se aplica apenas se libere. Un hub que no
     * contesta no se reintenta, para no trabar este hilo cada segundo.
     */
    private fun applyHubRest() {
        if (!configLoaded) return
        val deviceId = ykush.usableDeviceId()
        val blocked = hubRestBlocked()
        if (blocked) {
            if (!hubRest.thermometerDue(deviceId)) return
            hubRest.thermometerDone(deviceId)
        } else {
            if (!hubRest.due(deviceId)) return
            hubRest.done(deviceId)
        }
        val cfg = config
        val kiosk = kioskVisible
        val wanted = HubRestState.wanted(cfg, kiosk)
        val states = ykush.readAll()
        if (states.all { it == null }) {
            log("[YKUSH] El hub no contesta: los puertos quedan como están")
            return
        }
        if (blocked) {
            for ((port, on) in HubRestState.changes(wanted, states, blocked = true, cfg.ykushThermometerPort)) {
                if (ykush.setPort(port, on)) log("[YKUSH] Puerto $port del termómetro encendido")
            }
            return
        }
        var interrupted = false
        for ((port, on) in HubRestState.changes(wanted, states)) {
            // La lectura y cada orden tardan: entre tanto pudo abrirse el panel o una consulta.
            // El termómetro se enciende igual; lo demás se sigue después.
            if (hubRestBlocked() && !(on && port == cfg.ykushThermometerPort)) {
                interrupted = true
                continue
            }
            ykush.setPort(port, on)
        }
        if (interrupted) {
            log("[YKUSH] Se usó un equipo mientras se cambiaban los puertos: se sigue después")
            hubRest.again()
            return
        }
        log(HubRestState.logLine(wanted, kiosk, ::logText))
    }

    /** No se le apaga un equipo a nadie, ni a Testing el que encendió a mano. */
    private fun hubRestBlocked(): Boolean =
        sessionActive || testing || testingScreenOpen || localMode || tele.usingInstrument

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
                lastPost = txt(R.string.rt_post_error, error.message ?: error.javaClass.simpleName)
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
        // El termómetro va antes de validar: su lectura sola también se manda. Sin consulta, sólo una reciente.
        val window = sendableWindow(taken, thermometerForPacket(thermo.sample(), active, now)) ?: return
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
        lastPost = withDropped(TotemStatus.lastPost(outcome, active))
        // Si mientras viajaba el POST llegó un inicio o un fin, la respuesta ya no cuenta.
        if (!active) {
            if (outcome == PostOutcome.Ok) resumeSession(epoch)
            return
        }
        if (!watch.sent(outcome, epoch, System.nanoTime())) return
        stopSession("la plataforma no tiene la toma abierta") { gate.epoch == epoch }
    }

    /** Si la cola tiró paquetes en esta consulta, el último envío lo dice. */
    private fun withDropped(text: Txt): Txt = TotemStatus.withDropped(text, droppedPackets.get())

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
     * limpia nada de la plaqueta: lo que se acaba de mandar ya es parte de la
     * toma. Sí la lectura del termómetro si no es reciente: es de otra persona
     * y, si quedara, saldría en cada paquete como temperatura de este paciente.
     * Una reciente se conserva: es de quien se midió mientras la tablet se
     * perdía el inicio.
     */
    private fun resumeSession(epoch: Long) {
        synchronized(sessionLock) {
            // Un POST de prueba que salió justo antes de abrir el Panel Local no arranca la consulta.
            if (!gate.resumeSession(epoch)) return
            thermo.resetLatestIfStale(System.nanoTime())
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
        val notification = TotemStatus.notification(
            permissionMissing = hub.permissionMissing(),
            berryUp = berryUp,
            localMode = localMode,
            active = active,
        )
        // El decimal lo escribe la pantalla con el idioma del diccionario (fmt_locale).
        val lastData = TotemStatus.lastData(berry.lastDataAgeSeconds())
        val others = TotemStatus.others(
            thermoConnected = thermo.connected,
            thermoMeasured = thermo.measured,
            hubPlugged = ykush.plugged(),
            active = active,
            missing = config.missingFields(),
            permissionMissing = hub.permissionMissing(),
        )
        state.value = TotemState(
            heartRate = display(vitals.heartRate),
            respRate = display(vitals.respRate),
            spo2Pulse = display(vitals.spo2Pulse),
            temperature = if (seen || thermo.latest() != null) temperature else "—",
            nibp = display(vitals.nibp),
            berryUp = berryUp,
            lastData = lastData,
            pusherUp = pusherUp,
            thermoConnected = thermo.connected,
            thermoMeasured = thermo.measured,
            others = others,
            lastPost = lastPost,
            notification = notification,
        )
        onNotification(notification)
    }

    /** Devuelve el motivo si no se puede probar ahora. */
    fun startTesting(): Txt? {
        if (sessionActive) return txt(R.string.rt_test_blocked_session)
        // Al revés no: abrir el panel sí corta la prueba. Testing es diagnóstico y el
        // panel está atendiendo a un paciente; en pantalla dividida no se le saca la presión.
        if (localMode) return txt(R.string.rt_test_blocked_local)
        testingSession.reset()
        testing = true
        log("[TEST] Prueba iniciada: no se envía nada a la API")
        return null
    }

    /**
     * Lo llama Testing en onStart y onStop. Al cerrarse se buscan de nuevo los
     * puertos que correspondan, en la vuelta siguiente de [watchLoop].
     */
    fun setTestingScreenOpen(open: Boolean) {
        if (testingScreenOpen == open) return
        testingScreenOpen = open
        if (!open) {
            log("[YKUSH] Se cerró Testing: se buscan los puertos que correspondan")
            hubRest.again()
        }
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

    fun testNibpStart(): NibpReply {
        if (!testing) return NibpReply(ok = false, txt(R.string.rt_test_start_first))
        if (sessionActive) return NibpReply(ok = false, txt(R.string.rt_session_busy))
        if (!berry.startNibp()) return NibpReply(ok = false, txt(R.string.rt_nibp_start_failed))
        resetNibp()
        return NibpReply(ok = true, txt(R.string.rt_nibp_measuring))
    }

    fun testNibpStop(): NibpReply {
        // Una presión pedida por el médico, o por quien atiende en el Panel Local, no se para desde Testing.
        if (sessionActive) return NibpReply(ok = false, txt(R.string.rt_session_busy))
        if (localMode) return NibpReply(ok = false, txt(R.string.rt_nibp_local_owns))
        berry.stopNibp()
        return NibpReply(ok = true, txt(R.string.rt_nibp_stop_sent))
    }

    /** Devuelve el motivo si el Panel Local no puede abrirse ahora. */
    fun startLocal(): Txt? {
        // Dentro del lock: una prueba aceptada no puede arrancar la consulta entre el chequeo y el cambio.
        synchronized(sessionLock) {
            if (!gate.openLocal()) return txt(R.string.rt_local_blocked_session)
        }
        // El panel usa el otoscopio y el estetoscopio: la teleconsulta (o su prueba) los suelta.
        tele.unpublishAll("se abrió el Panel Local", includeManual = true)
        // El panel arranca en blanco: ni ondas ni el estado de una presión de antes.
        live.clear()
        // Ni la temperatura ni la presión de la persona anterior: una presión se ve sólo si se pide acá.
        thermo.resetLatest()
        localNibpAsked = false
        // El estetoscopio, como al instalar: Corazón y el volumen de arranque.
        stetho.filter = StethoFilter.DEFAULT
        stetho.volume = StethoDsp.DEFAULT_VOLUME
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
        if (!localMode) return
        // Lo que se midió en el panel muere con el panel: no sale nunca a OnDoctor. Se borra
        // antes de soltar el modo, así la prueba que sale apenas se cierra ya no la encuentra.
        thermo.resetLatest()
        if (!gate.closeLocal()) return
        localNibpAsked = false
        // Una presión pedida desde el panel no queda inflando el manguito.
        stopNibpInBackground()
        stopStethoscope(closing = true)
        // La pantalla ya soltó la cámara del otoscopio; acá se apaga su puerto.
        localOtoscopeOff()
        log("[LOCAL] Panel Local cerrado")
        publish()
    }

    fun localNibpStart(): NibpReply {
        if (!localMode) return NibpReply(ok = false, txt(R.string.rt_panel_inactive))
        if (!berry.startNibp()) return NibpReply(ok = false, txt(R.string.rt_nibp_start_failed))
        resetNibp()
        localNibpAsked = true
        return NibpReply(ok = true, txt(R.string.rt_nibp_measuring))
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

    fun localNibpStop(): NibpReply {
        if (!localMode) return NibpReply(ok = false, txt(R.string.rt_panel_inactive))
        berry.stopNibp()
        return NibpReply(ok = true, txt(R.string.rt_nibp_stopped))
    }

    /**
     * Escuchar: enciende en el YKUSH3 el puerto del adaptador si está apagado
     * y, si lo encendió, espera hasta 5 s a que Android vea la placa de audio
     * (y la salida, el jack de la tablet o un auricular USB) antes de abrir el audio. Si no
     * encendió nada, abre con lo que hay. Sin YKUSH3 se intenta directo.
     * Todo en otro hilo; [done] se llama desde ese hilo con el mensaje para la
     * tarjeta.
     */
    fun localStethoStart(done: (Txt) -> Unit) {
        if (!localMode) {
            done(txt(R.string.rt_panel_inactive))
            return
        }
        if (stetho.running || stethoStarting) {
            done(txt(if (stethoStarting) R.string.rt_stetho_starting else R.string.rt_stetho_already))
            return
        }
        stethoStarting = true
        val epoch = stethoEpoch
        stethoWorker.execute {
            try {
                done(startStethoscope(epoch))
            } catch (error: Exception) {
                log("[ESTETO] Falló al empezar: $error")
                done(txt(R.string.rt_stetho_start_failed, error.message ?: error.javaClass.simpleName))
            } finally {
                stethoStarting = false
            }
        }
    }

    private fun startStethoscope(epoch: Long): Txt {
        val cfg = config
        val notes = ArrayList<Txt>()
        val hubPresent = ykush.plugged()
        var poweredSome = false
        if (hubPresent) {
            // Sólo el adaptador: el termómetro queda siempre encendido y los auriculares van al jack.
            val ports = listOf(cfg.ykushStethoscopePort)
            val states = ykush.readAll()
            for (port in stethoPorts.toTurnOn(ports, states)) {
                if (stethoEpoch != epoch) return NO_TEXT
                if (ykush.setPort(port, true)) {
                    // Sólo si se leyó apagado se apaga al salir; si no se pudo leer, queda encendido.
                    if (!stethoPorts.claim(port, states.getOrNull(port - 1))) {
                        log("[ESTETO] No se pudo leer el puerto $port antes de encenderlo: queda encendido al salir")
                    }
                    poweredSome = true
                } else {
                    notes += txt(R.string.rt_stetho_port_failed, port)
                }
            }
        } else {
            notes += txt(R.string.rt_stetho_no_hub)
        }
        var found = stetho.devices(cfg)
        // Encender un puerto es enchufar el equipo: Android tarda un momento en ver
        // la placa de audio. Si no se encendió nada, lo que no está no va a aparecer
        // solo; sin YKUSH3 sí se le da tiempo al adaptador, que pudo recién enchufarse.
        // Con auriculares en el jack de la tablet la salida ya está: se espera sólo al adaptador.
        val wait = poweredSome || (!hubPresent && found.input == null)
        val deadline = System.nanoTime() + if (wait) STETHO_WAIT_NANOS else 0L
        while ((found.input == null || found.output == null) && System.nanoTime() < deadline) {
            if (stethoEpoch != epoch || !localMode) return NO_TEXT
            sleep(DEVICE_POLL_MS)
            found = stetho.devices(cfg)
        }
        // Parar o cerrar el panel durante la espera: no se abre nada.
        if (stethoEpoch != epoch || !localMode) return NO_TEXT
        val reason = stetho.start(cfg)
        // paso 4b: el motivo llega armado de StethoscopeLink.
        if (reason != null) return join(notes + raw(reason), " ")
        if (!stetho.playing) notes += txt(R.string.rt_stetho_no_headphones)
        return join(listOf(txt(R.string.rt_stetho_listening)) + notes, " ")
    }

    /**
     * Parar: deja de escuchar; los puertos del YKUSH3 quedan como están. Si
     * estaba grabando, la grabación se guarda con lo que tenga (o se descarta
     * si no llegó a 1 s).
     */
    fun localStethoStop(done: (Txt) -> Unit) {
        stopStethoscope(closing = false)
        stethoWorker.execute { done(txt(R.string.rt_stetho_stopped)) }
    }

    /** Grabar 15 s de lo que se está escuchando, en memoria. */
    fun localStethoRecord(): Txt =
        txt(if (stetho.startRecording()) R.string.rt_stetho_recording else R.string.rt_stetho_record_needs_listening)

    /** Corta la grabación en curso y la guarda si pasó de 1 s. */
    fun localStethoStopRecording(): Txt =
        txt(if (stetho.stopRecording() != null) R.string.rt_stetho_recording_saved else R.string.rt_stetho_recording_short)

    /**
     * Reproduce una grabación por el auricular. Si se está escuchando, primero
     * se para la escucha (y lo que se estuviera grabando se guarda): las dos
     * no se superponen y el adaptador no queda abierto mientras suena la
     * grabación. En el hilo del estetoscopio, después de parar; [done] se
     * llama desde ahí con el mensaje para el panel.
     */
    fun localStethoPlay(number: Int, done: (Txt) -> Unit) {
        if (!localMode) {
            done(txt(R.string.rt_panel_inactive))
            return
        }
        if (stetho.running || stethoStarting) stopStethoscope(closing = false)
        stethoWorker.execute {
            if (!localMode) return@execute
            // paso 4b: el motivo llega armado de StethoscopeLink.
            done(stetho.play(number, config)?.let(::raw) ?: txt(R.string.rt_stetho_playing, number))
        }
    }

    fun localStethoStopPlayback(done: (Txt) -> Unit) {
        stethoWorker.execute {
            stetho.stopPlayback()
            done(NO_TEXT)
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
    fun localOtoscopeOn(progress: (Txt) -> Unit, done: (OtoscopePower.Path?, Txt) -> Unit) {
        if (!localMode) {
            done(null, txt(R.string.rt_panel_inactive))
            return
        }
        val hubPresent = ykush.plugged()
        val actions = otoscope.switchOn(hubPresent, System.nanoTime())
        if (actions == null) {
            done(null, txt(R.string.rt_otoscope_busy))
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
                done(null, txt(R.string.rt_otoscope_on_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun waitForOtoscope(
        actions: List<OtoscopePower.Action>,
        port: Int,
        progress: (Txt) -> Unit,
        done: (OtoscopePower.Path?, Txt) -> Unit,
    ) {
        if (OtoscopePower.Action.PORT_ON in actions) {
            progress(txt(R.string.rt_otoscope_powering))
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
            progress(txt(R.string.rt_otoscope_waiting_camera))
        } else {
            progress(txt(R.string.rt_otoscope_no_hub_waiting))
        }
        while (true) {
            when (val poll = otoscope.poll(System.nanoTime(), otoscopeProbe.cameraSeen(), otoscopeProbe.usbReady())) {
                OtoscopePower.Poll.Waiting -> sleep(DEVICE_POLL_MS)
                OtoscopePower.Poll.Cancelled -> return
                is OtoscopePower.Poll.Open -> {
                    log(
                        if (poll.path == OtoscopePower.Path.CAMERA2) "[OTOSCOPIO] Apareció: se abre por Camera2"
                        else "[OTOSCOPIO] Apareció: se abre por UVC",
                    )
                    done(poll.path, txt(R.string.rt_otoscope_opening))
                    return
                }
                is OtoscopePower.Poll.TimedOut -> {
                    val next = OtoscopePower.afterTimeout(
                        portOff = OtoscopePower.Action.PORT_OFF in poll.actions,
                        usbPresent = otoscopeProbe.usbPresent(),
                        usbReady = otoscopeProbe.usbReady(),
                    )
                    if (next == OtoscopePower.Timeout.ASK_PERMISSION) {
                        // Enchufado pero sin permiso: alguien cerró el pedido de Android. Se pide de
                        // nuevo (apagando y encendiendo el puerto, así vuelve el pedido con la casilla)
                        // y el puerto queda encendido. Si lo encendió el panel, se apaga al salir.
                        log("[OTOSCOPIO] Está enchufado pero sin permiso USB: se vuelve a pedir")
                        askOtoscopePermission()
                        done(null, txt(R.string.rt_otoscope_permission))
                        return
                    }
                    if (next == OtoscopePower.Timeout.PORT_OFF) turnOffOtoscopePort()
                    log("[OTOSCOPIO] No apareció en ${OTOSCOPE_WAIT_NANOS / 1_000_000_000L} s")
                    done(null, txt(R.string.rt_otoscope_missing))
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

    /**
     * Lo que muestra el panel lateral, sin efectos: se puede pedir cada medio
     * segundo desde la pantalla. No usa [testingSnapshot], que avanza la prueba.
     */
    fun statusSnapshot(): StatusSnapshot = StatusSnapshot(
        local = localSnapshot(),
        wifi = wifi.state,
        ykushPlugged = hub.ykushPlugged(),
        usbMissing = hub.missingPermissions(),
        appMissing = AppPermissions.missingForPanel { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
        },
        localMode = localMode,
        sessionActive = sessionActive,
        teleInRoom = tele.status.room != RoomState.DISCONNECTED,
        callAudioChoice = config.callAudioOutput,
        jackPresent = jackPresent(),
    )

    /** El texto que bloquea el botón de cambiar de modo, o null si se puede cambiar. */
    fun modeSwitchBlock(): Txt? =
        StatusSnapshot.modeSwitchBlock(sessionActive, tele.status.room != RoomState.DISCONNECTED)

    /**
     * El segmentado de sonido del panel lateral: guarda [choice] ("auricular"
     * o "parlante") en la configuración, fuera del hilo principal. El cambio
     * vuelve por [ConfigStore.flow] y ahí se llama a `callAudio.refresh()`,
     * como al guardarlo desde la configuración. Antes de leer lo guardado no
     * se escribe nada: se pisaría la configuración con la de fábrica.
     */
    fun setCallAudioOutput(choice: String) {
        if (!configLoaded || config.callAudioOutput == choice) return
        val saved = config.copy(callAudioOutput = choice)
        scope.launch {
            try {
                store.save(saved)
                log("[AUDIO] Sonido de la llamada desde el panel lateral: $choice")
            } catch (error: Exception) {
                log("[AUDIO] No se pudo guardar el sonido de la llamada: $error")
            }
        }
    }

    /** Si hay algo enchufado en el jack de la tablet: Android lo lista sólo mientras está. */
    private fun jackPresent(): Boolean =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { AudioPick.isJack(it.type) } == true

    fun localSnapshot(): LocalSnapshot {
        val stats = parser.stats()
        val sample = thermo.sample()
        val plugged = hub.panelDevices()
        return LocalSnapshot(
            berryUp = berry.connected,
            lastDataAge = berry.lastDataAgeSeconds(),
            vitals = stats.vitals,
            ecgInfo = stats.ecgInfo,
            thermoConnected = thermo.connected,
            thermoReading = sample?.let { s -> formatThermo(s.reading) },
            // El termómetro guarda el instante en nanoTime; la pantalla quiere la hora del reloj.
            thermoAtMillis = sample?.let { s -> System.currentTimeMillis() - (System.nanoTime() - s.atNano) / 1_000_000L },
            nibpRunning = berry.nibpRunning,
            nibp = live.nibp,
            nibpAsked = localNibpAsked,
            nibpAtMillis = nibpResultAtMillis.takeIf { it != 0L && localNibpAsked },
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

    /**
     * Si la teleconsulta puede usar [instrument] ahora; si no, el motivo. Con
     * el Panel Local abierto los instrumentos son del panel. Sin el permiso o
     * sin el tipo de servicio, Android le da a Berry Med una cámara que no
     * abre o un micrófono mudo.
     */
    private fun teleBlocked(instrument: Instrument): Txt? {
        if (localMode) return txt(R.string.rt_tele_local_open)
        val (permission, type) = when (instrument) {
            Instrument.OTOSCOPE -> Pair(Manifest.permission.CAMERA, ForegroundAccess.CAMERA)
            Instrument.STETHOSCOPE -> Pair(Manifest.permission.RECORD_AUDIO, ForegroundAccess.MICROPHONE)
        }
        val camera = instrument == Instrument.OTOSCOPE
        if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
            return txt(if (camera) R.string.rt_tele_camera_permission else R.string.rt_tele_microphone_permission)
        }
        if (Build.VERSION.SDK_INT >= 30 && foregroundTypes and type == 0) {
            return txt(if (camera) R.string.rt_tele_camera_type else R.string.rt_tele_microphone_type)
        }
        return null
    }

    /**
     * Cada dirección que carga el kiosco: si trae `serverUrl` y `token`, se
     * entra a la sala del encuentro; si es el inicio, el login o el setup, se
     * sale. Lo llama la pantalla del kiosco; el token no se escribe en ningún lado.
     */
    fun onKioskUrl(url: String) {
        val found = KioskUrls.credentials(url)
        if (found != null) {
            tele.onKioskToken(found)
            return
        }
        if (KioskUrls.isHome(url, config.kioskUrl)) tele.onKioskHome()
    }

    /** La prueba manual de Testing: publica [instrument] con un servidor y un token hechos a mano. */
    fun teleManualStart(serverUrl: String, token: String, instrument: Instrument) =
        tele.startManual(serverUrl, token, instrument)

    fun teleManualStop() = tele.stopManual()

    fun teleView(): TeleView = TeleView(
        status = tele.status,
        serviceTypes = ForegroundAccess.describe(foregroundTypes),
        serviceCamera = Build.VERSION.SDK_INT < 30 || ForegroundAccess.hasCamera(foregroundTypes),
        serviceMicrophone = Build.VERSION.SDK_INT < 30 || ForegroundAccess.hasMicrophone(foregroundTypes),
        videoPath = teleBackend.videoPath,
        videoSize = teleBackend.videoSize,
        framesPerSecond = teleBackend.framesPerSecond(),
        levelPercent = StethoDsp.levelPercent(teleBackend.dsp.level),
        silenced = teleBackend.captureSilenced(),
        callAudio = callAudio.describe(),
    )

    /** Lee los tres puertos del YKUSH3 sin cambiarlos. Bloquea hasta tres segundos: llamarlo fuera del hilo de la pantalla. */
    fun testYkushRead(): Txt {
        if (!ykush.plugged()) return txt(R.string.rt_hub_none)
        if (hub.permissionMissing() && ykush.lastStates.all { it == null }) hub.forgetAsked()
        val states = ykush.readAll()
        if (states.all { it == null }) return txt(R.string.rt_hub_no_answer)
        return txt(R.string.rt_hub_state_read)
    }

    /**
     * Enciende o apaga un puerto del YKUSH3 desde Testing. Con una consulta
     * enviando o el Panel Local abierto no se toca nada: podría dejar al
     * médico, o a quien atiende, sin un equipo.
     * Bloquea hasta cuatro segundos: llamarlo fuera del hilo de la pantalla.
     */
    fun testYkushSet(port: Int, on: Boolean): Txt = when {
        sessionActive -> txt(R.string.rt_hub_busy_session)
        // Lo mismo con el panel: podría apagarle el otoscopio o el estetoscopio a quien atiende.
        localMode -> txt(R.string.rt_hub_busy_local)
        !ykush.plugged() -> txt(R.string.rt_hub_none)
        !ykush.setPort(port, on) -> txt(R.string.rt_hub_order_rejected)
        !on && port == config.ykushThermometerPort -> {
            // Es una prueba a mano: el termómetro vuelve a encenderse en la vuelta siguiente de watchLoop.
            hubRest.again()
            txt(R.string.rt_hub_thermo_off, port)
        }
        else -> txt(if (on) R.string.rt_hub_port_turned_on else R.string.rt_hub_port_turned_off, port)
    }

    /**
     * Guarda el estado del kiosco como arranque del hub, para los segundos entre
     * que recibe energía y arranca el servicio: la tablet arranca en el kiosco.
     * Con el termómetro encendido, como siempre. Un puerto libre no se manda. Es persistente en el hub: sale sólo con este
     * botón. Fuera del hilo de la pantalla.
     */
    fun testYkushSaveBoot(): Txt {
        if (!ykush.plugged()) return txt(R.string.rt_hub_none)
        val wanted = HubRestState.wanted(config, kiosk = true)
        val rejected = wanted.mapIndexedNotNull { index, on ->
            val port = index + 1
            port.takeIf { on != null && !ykush.setDefaultState(port, on) }
        }
        return if (rejected.isEmpty()) {
            txt(R.string.rt_hub_boot_saved, HubRestState.describe(wanted))
        } else {
            txt(R.string.rt_hub_boot_rejected, raw(rejected.joinToString(", ")))
        }
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
        val sample = thermo.sample()
        val active = sessionActive
        val cfg = config
        // Lo mismo que saldría: con el termómetro aplicado antes de validar, y con la misma regla de sendOnce.
        val sendable = thermometerForPacket(sample, active, System.nanoTime())
        val preview = lastWindow?.let { window -> sendableWindow(window, sendable) }?.let { ready ->
            metricsEnvelope(System.currentTimeMillis(), splitWindow(ready).last())
        }
        return TestingSnapshot(
            testing = testing,
            sessionActive = active,
            berryUp = berryUp,
            berryPort = berry.portName(),
            lastDataAge = berry.lastDataAgeSeconds(),
            rates = testingSession.rates(),
            verdicts = verdicts,
            stats = stats,
            nibpRunning = berry.nibpRunning,
            thermoConnected = thermo.connected,
            thermoPort = thermo.portName,
            thermoReading = sample?.let { "${it.reading.value} ${it.reading.unit}" },
            thermoAge = sample?.let { (System.nanoTime() - it.atNano) / 1_000_000_000L },
            thermoLine = thermo.lastLine,
            ykushPlugged = ykush.plugged(),
            ykushPorts = cfg.ykushPortRoles().mapIndexed { index, role ->
                val port = index + 1
                val label = if (port == cfg.ykushThermometerPort) txt(R.string.rt_port_role_auto_on, role) else role
                YkushPortView(port = port, role = label, on = ykush.lastStates.getOrNull(index))
            },
            inventory = inventorySnapshot(),
            url = cfg.metricsUrl(),
            tele = teleView(),
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

    /**
     * Un mensaje de pantalla dentro de una línea de log: siempre en español,
     * sea cual sea el idioma de la pantalla. Lo reciben como función las
     * reglas sin Android que arman su línea de log.
     */
    internal fun logText(text: Txt): String = logLanguage.resolve(text)

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val TAG = "BerryMed"

        /** Un mensaje vacío: borra el de la tarjeta (al parar una grabación) o no dice nada (una espera abandonada). */
        private val NO_TEXT: Txt = raw("")
        private const val TICK_NANOS = 1_000_000_000L
        private const val CONFIG_RETRY_MS = 5_000L
        private const val INVENTORY_NANOS = 3L * 1_000_000_000L
        private const val STETHO_WAIT_NANOS = 5L * 1_000_000_000L
        private const val DEVICE_POLL_MS = 250L
        private const val OTOSCOPE_WAIT_NANOS = 8L * 1_000_000_000L

        /** Lo que queda apagado el puerto del otoscopio para que Android lo vea como recién conectado. */
        private const val OTOSCOPE_CYCLE_OFF_MS = 2_000L

        /** Visto el USB del otoscopio, cuánto se espera a que la tablet lo liste como cámara antes de ir por UVC. */
        private const val CAMERA2_GRACE_NANOS = 2L * 1_000_000_000L
    }
}
