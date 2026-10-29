// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.otoscope.CameraXOtoscopeFrames
import com.guardiavirtual.berrymed.otoscope.OtoscopeFrames
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.otoscope.OtoscopeProbe
import com.guardiavirtual.berrymed.otoscope.OtoscopeResolution
import com.guardiavirtual.berrymed.otoscope.UvcOtoscopeFrames
import com.guardiavirtual.berrymed.service.PanelPorts
import com.guardiavirtual.berrymed.usb.YkushHub
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.video.VideoFrameCapturer
import io.livekit.android.ConnectOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * La sala de LiveKit de verdad: el SDK, el otoscopio, el adaptador del
 * estetoscopio y el YKUSH3. Todo lo llama [TeleStreamer] desde su hilo.
 *
 * El audio es lo delicado: con Chrome en la llamada de Zoom, Berry Med no
 * puede tocar el `AudioManager` (ni modo, ni foco, ni ruteo) porque le rompe
 * el audio a Zoom. Por eso el manejador de audio del SDK es [NoAudioHandler],
 * el tipo de audio es el de medios (sin `MODE_IN_COMMUNICATION` ni el truco
 * del audio mudo que lo sostiene) y el módulo de audio de WebRTC lo arma acá:
 * fuente `UNPROCESSED` (o `MIC`), sin cancelación de eco ni supresión de
 * ruido por hardware, en mono a 48 kHz, con el adaptador como dispositivo
 * preferido y el filtro Amplio aplicado al buffer de captura antes de
 * codificar ([StethoStreamDsp]). La sala no se suscribe a nada: Berry Med no
 * reproduce nada.
 */
class LiveKitBackend(
    context: Context,
    private val config: () -> BerryConfig,
    private val ykush: YkushHub,
    private val probe: OtoscopeProbe,
    /** El adaptador del estetoscopio según la configuración, o null si no está. */
    private val stethoInput: (BerryConfig) -> AudioDeviceInfo?,
    /** null si el instrumento se puede usar; si no, el motivo. Lo decide el runtime. */
    private val blockedBy: (Instrument) -> String?,
    private val log: (String) -> Unit,
) : TeleBackend {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tokens = TeleTokenClient()

    /** Lo avisa el SDK cuando la sala se cae del todo; lo conecta el runtime con [TeleStreamer.roomLost]. */
    var onRoomLost: (String) -> Unit = {}

    /** Abrir y cerrar el otoscopio por UVC bloquea: un hilo propio, como en el Panel Local. */
    private val uvcWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-tele-uvc").apply { isDaemon = true }
    }

    /** Lo que encendió la teleconsulta en el YKUSH3: sólo eso se apaga al terminar. */
    private val otoscopePorts = PanelPorts()
    private val stethoPorts = PanelPorts()

    val dsp = StethoStreamDsp()

    // Sólo desde el hilo del streamer, salvo los @Volatile que lee Testing.
    private var room: Room? = null
    private var module: JavaAudioDeviceModule? = null
    private var scope: CoroutineScope? = null
    @Volatile private var closing = false
    private var frames: OtoscopeFrames? = null
    private var capturer: VideoFrameCapturer? = null
    private var videoTrack: LocalVideoTrack? = null
    private var audioTrack: LocalAudioTrack? = null
    @Volatile private var audioSource = MediaRecorder.AudioSource.MIC
    @Volatile var videoPath: String? = null
        private set
    @Volatile var videoSize: String? = null
        private set

    /** Cuadros del otoscopio entregados a WebRTC desde que se publicó. */
    private val framesSent = AtomicLong()
    private var fpsMark = Pair(0L, System.nanoTime())

    override fun blocked(instrument: Instrument): String? = blockedBy(instrument)

    override fun fetchToken(encounterId: String): TokenOutcome {
        val url = config().livekitTokenUrl() ?: return TokenOutcome.Failed("Falta configurar la URL de la API o el tótem")
        return tokens.fetch(url, encounterId)
    }

    override fun connect(credentials: TeleCredentials): String? {
        disconnect()
        closing = false
        val adm = buildAudioModule()
        module = adm
        val lk = LiveKit.create(
            appContext,
            RoomOptions(adaptiveStream = false, dynacast = false),
            LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioOutputType = AudioType.MediaAudioType(),
                    audioHandler = NoAudioHandler(),
                    audioDeviceModule = adm,
                    disableCommunicationModeWorkaround = true,
                    disableAudioPrewarming = true,
                ),
            ),
        )
        room = lk
        val events = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = events
        events.launch {
            lk.events.collect { event ->
                if (event is RoomEvent.Disconnected && !closing) {
                    onRoomLost(event.reason.name.lowercase())
                }
            }
        }
        return try {
            runBlocking {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    lk.connect(credentials.serverUrl, credentials.token, ConnectOptions(autoSubscribe = false))
                }
            }
            null
        } catch (error: Throwable) {
            disconnect()
            "No se pudo entrar a la sala: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    override fun publish(instrument: Instrument): String? = when (instrument) {
        Instrument.OTOSCOPE -> publishOtoscope()
        Instrument.STETHOSCOPE -> publishStethoscope()
    }

    override fun unpublish(instrument: Instrument) {
        when (instrument) {
            Instrument.OTOSCOPE -> releaseOtoscope()
            Instrument.STETHOSCOPE -> releaseStethoscope()
        }
    }

    override fun disconnect() {
        // Si quedó algo publicado (un error a mitad de camino), se suelta igual.
        if (videoTrack != null || frames != null) releaseOtoscope()
        if (audioTrack != null) releaseStethoscope()
        closing = true
        val old = room
        room = null
        try {
            old?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            old?.release()
        } catch (_: Throwable) {
        }
        scope?.cancel()
        scope = null
        // El módulo de audio es nuestro (LiveKit no lo suelta): va después de la sala.
        try {
            module?.release()
        } catch (_: Throwable) {
        }
        module = null
    }

    private fun buildAudioModule(): JavaAudioDeviceModule {
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        audioSource = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        return JavaAudioDeviceModule.builder(appContext)
            .setAudioSource(audioSource)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setSampleRate(SAMPLE_RATE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioBufferCallback { buffer, format, channels, rate, bytes, captureTimeNs ->
                if (format == AudioFormat.ENCODING_PCM_16BIT || format == AudioFormat.ENCODING_DEFAULT) {
                    dsp.process(buffer, bytes, rate, channels)
                }
                captureTimeNs
            }
            .createAudioDeviceModule()
    }

    private fun publishOtoscope(): String? {
        val lk = room ?: return "No hay sala"
        val cfg = config()
        powerOn(cfg.ykushOtoscopePort, otoscopePorts, "otoscopio")
        val path = waitForOtoscope() ?: run {
            powerOff(otoscopePorts, "otoscopio")
            return "El otoscopio no aparece ni como cámara ni por USB con permiso"
        }
        val source = when (path) {
            OtoscopePower.Path.CAMERA2 -> CameraXOtoscopeFrames(appContext, log)
            OtoscopePower.Path.UVC -> UvcOtoscopeFrames(appContext, probe, uvcWorker, log)
        }
        frames = source
        videoPath = source.label
        val frameCapturer = VideoFrameCapturer()
        capturer = frameCapturer
        val track = lk.localParticipant.createVideoTrack(
            name = OTOSCOPE_TRACK,
            capturer = frameCapturer,
            options = LocalVideoTrackOptions(
                position = null,
                captureParams = VideoCaptureParameter(
                    OtoscopeResolution.TARGET,
                    OtoscopeResolution.TARGET,
                    MAX_FPS,
                    adaptOutputToDimensions = false,
                ),
            ),
        )
        videoTrack = track
        track.startCapture()
        framesSent.set(0)
        fpsMark = Pair(0L, System.nanoTime())
        val opened = CountDownLatch(1)
        var reason: String? = "El otoscopio no abrió en ${OPEN_TIMEOUT_MS / 1000} s"
        source.start(
            sink = { frame ->
                if (framesSent.getAndIncrement() == 0L) videoSize = "${frame.rotatedWidth}×${frame.rotatedHeight}"
                frameCapturer.pushVideoFrame(frame)
            },
            done = { result ->
                reason = result
                opened.countDown()
            },
        )
        opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        reason?.let {
            releaseOtoscope()
            return it
        }
        val published = try {
            runBlocking {
                lk.localParticipant.publishVideoTrack(
                    track,
                    VideoTrackPublishOptions(
                        name = OTOSCOPE_TRACK,
                        videoEncoding = VideoEncoding(VIDEO_BITRATE, MAX_FPS),
                        simulcast = false,
                        source = Track.Source.CAMERA,
                        // La imagen del otoscopio no se achica: si falta ancho de banda, se bajan los cuadros.
                        degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION,
                    ),
                )
            }
        } catch (error: Throwable) {
            log("[TELE] Error al publicar el video: $error")
            false
        }
        if (!published) {
            releaseOtoscope()
            return "LiveKit no aceptó la pista del otoscopio"
        }
        return null
    }

    /** Espera el otoscopio con la misma regla que el Panel Local. null si no apareció. */
    private fun waitForOtoscope(): OtoscopePower.Path? {
        val power = OtoscopePower(OTOSCOPE_WAIT_NANOS, CAMERA2_GRACE_NANOS)
        // El puerto ya lo manejó powerOn: acá sólo se espera.
        power.switchOn(hubPresent = false, now = System.nanoTime())
        while (true) {
            when (val poll = power.poll(System.nanoTime(), probe.cameraSeen(), probe.usbReady())) {
                OtoscopePower.Poll.Waiting -> Thread.sleep(POLL_MS)
                OtoscopePower.Poll.Cancelled -> return null
                is OtoscopePower.Poll.Open -> return poll.path
                is OtoscopePower.Poll.TimedOut -> return null
            }
        }
    }

    private fun releaseOtoscope() {
        val track = videoTrack
        videoTrack = null
        if (track != null) {
            try {
                room?.localParticipant?.unpublishTrack(track)
            } catch (_: Throwable) {
            }
            try {
                track.stop()
                track.dispose()
            } catch (_: Throwable) {
            }
        }
        capturer = null
        frames?.let { source ->
            val released = CountDownLatch(1)
            source.stop { released.countDown() }
            // El puerto se apaga después de soltar la cámara.
            released.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        frames = null
        videoPath = null
        videoSize = null
        powerOff(otoscopePorts, "otoscopio")
    }

    private fun publishStethoscope(): String? {
        val lk = room ?: return "No hay sala"
        val adm = module ?: return "No hay módulo de audio"
        val cfg = config()
        val powered = powerOn(cfg.ykushStethoscopePort, stethoPorts, "estetoscopio")
        var input = stethoInput(cfg)
        // Encender el puerto es enchufar el adaptador: Android tarda un momento en verlo.
        val deadline = System.nanoTime() + if (powered || input == null) STETHO_WAIT_NANOS else 0L
        while (input == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
            input = stethoInput(cfg)
        }
        if (input == null) {
            powerOff(stethoPorts, "estetoscopio")
            return "No aparece el adaptador del estetoscopio (\"${cfg.stethoInputName}\")"
        }
        adm.setPreferredInputDevice(input)
        dsp.reset()
        val track = lk.localParticipant.createAudioTrack(
            name = STETHO_TRACK,
            // Nada de procesamiento de voz: el eco, el ruido, la ganancia automática y el pasa-altos aplastan los latidos.
            options = LocalAudioTrackOptions(
                noiseSuppression = false,
                echoCancellation = false,
                autoGainControl = false,
                highPassFilter = false,
                typingNoiseDetection = false,
            ),
        )
        audioTrack = track
        val published = try {
            runBlocking {
                lk.localParticipant.publishAudioTrack(
                    track,
                    AudioTrackPublishOptions(
                        name = STETHO_TRACK,
                        audioBitrate = AUDIO_BITRATE,
                        // Sin DTX: con DTX Opus toma los silencios entre latidos como silencio y los corta.
                        dtx = false,
                        source = Track.Source.MICROPHONE,
                    ),
                )
            }
        } catch (error: Throwable) {
            log("[TELE] Error al publicar el audio: $error")
            false
        }
        if (!published) {
            releaseStethoscope()
            return "LiveKit no aceptó la pista del estetoscopio"
        }
        val sourceName = if (audioSource == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC"
        log("[TELE] Estetoscopio por ${input.productName} ($sourceName, filtro Amplio, $AUDIO_BITRATE bps, sin DTX)")
        return null
    }

    private fun releaseStethoscope() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                room?.localParticipant?.unpublishTrack(track)
            } catch (_: Throwable) {
            }
            try {
                track.stop()
                track.dispose()
            } catch (_: Throwable) {
            }
        }
        dsp.reset()
        powerOff(stethoPorts, "estetoscopio")
    }

    /**
     * Enciende [port] en el YKUSH3 si está apagado, con la regla del Panel
     * Local: sólo se anota para apagar al final si se leyó apagado de verdad.
     * Devuelve si encendió algo.
     */
    private fun powerOn(port: Int, owned: PanelPorts, what: String): Boolean {
        if (!ykush.plugged()) return false
        val states = ykush.readAll()
        if (owned.toTurnOn(listOf(port), states).isEmpty()) return false
        if (!ykush.setPort(port, true)) {
            log("[TELE] El YKUSH3 no encendió el puerto $port del $what: se espera igual")
            return false
        }
        if (!owned.claim(port, states.getOrNull(port - 1))) {
            log("[TELE] No se pudo leer el puerto $port antes de encenderlo: queda encendido al terminar")
        }
        log("[TELE] Puerto $port del $what encendido")
        return true
    }

    private fun powerOff(owned: PanelPorts, what: String) {
        val ports = owned.takeToTurnOff()
        if (ports.isEmpty() || !ykush.plugged()) return
        for (port in ports) ykush.setPort(port, false)
        log("[TELE] Se apagó el puerto ${ports.joinToString(", ")} del $what, que había encendido la teleconsulta")
    }

    /** Cuadros por segundo entregados desde la última vez que se preguntó. */
    @Synchronized
    fun framesPerSecond(): Double {
        val now = System.nanoTime()
        val count = framesSent.get()
        val (lastCount, lastAt) = fpsMark
        fpsMark = Pair(count, now)
        val seconds = (now - lastAt) / 1e9
        if (seconds <= 0.0 || count < lastCount) return 0.0
        return (count - lastCount) / seconds
    }

    /**
     * Si Android le da silencio a la captura de Berry Med: pasa cuando otra app
     * dueña del modo de comunicación (Chrome en Zoom) está capturando. null si
     * no hay captura o la tablet no lo informa (antes de Android 10).
     */
    fun captureSilenced(): Boolean? {
        if (Build.VERSION.SDK_INT < 29 || audioTrack == null) return null
        return try {
            audio.activeRecordingConfigurations
                .firstOrNull { it.clientAudioSource == audioSource }
                ?.isClientSilenced
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val OTOSCOPE_TRACK = "otoscopio"
        const val STETHO_TRACK = "estetoscopio"
        private const val SAMPLE_RATE = 48_000
        private const val MAX_FPS = 30
        private const val VIDEO_BITRATE = 1_500_000
        private const val AUDIO_BITRATE = 64_000
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val CLOSE_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 250L
        private const val OTOSCOPE_WAIT_NANOS = 8L * 1_000_000_000L
        private const val CAMERA2_GRACE_NANOS = 2L * 1_000_000_000L
        private const val STETHO_WAIT_NANOS = 5L * 1_000_000_000L
    }
}
