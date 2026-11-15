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
import com.guardiavirtual.berrymed.otoscope.OtoscopeReconnect
import com.guardiavirtual.berrymed.otoscope.OtoscopeResolution
import com.guardiavirtual.berrymed.otoscope.UvcOtoscopeFrames
import com.guardiavirtual.berrymed.service.InstrumentPorts
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

    /** Lo avisa el SDK cuando la sala se cae del todo; lo conecta el runtime con [TeleStreamer.roomLost]. */
    var onRoomLost: (String) -> Unit = {}

    /** Abrir y cerrar el otoscopio por UVC bloquea: un hilo propio, como en el Panel Local. */
    private val uvcWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-tele-uvc").apply { isDaemon = true }
    }

    /**
     * El puerto del YKUSH3 que se encendió para cada instrumento: se apaga al
     * soltarlo. En reposo están apagados, así que lo que enciende la
     * teleconsulta es siempre suyo. Sólo desde el hilo del streamer.
     */
    private var otoscopePort: Int? = null
    private var stethoPort: Int? = null

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
            "No se pudo entrar a la sala: ${redact(error.message ?: error.javaClass.simpleName, credentials.token)}"
        }
    }

    override fun publish(instrument: Instrument, cancelled: () -> Boolean): String? = when (instrument) {
        Instrument.OTOSCOPE -> publishOtoscope(cancelled)
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
        // Un otoscopio que quedó esperando el permiso USB tiene el puerto encendido sin publicar.
        otoscopePort = powerOff(otoscopePort, "otoscopio")
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

    private fun publishOtoscope(cancelled: () -> Boolean): String? {
        val lk = room ?: return "No hay sala"
        val cfg = config()
        otoscopePort = cfg.ykushOtoscopePort
        powerFor(cfg.ykushOtoscopePort, cfg.ykushStethoscopePort, "otoscopio")
        val path = waitForOtoscope(cancelled) ?: run {
            if (cancelled()) {
                otoscopePort = powerOff(otoscopePort, "otoscopio")
                return FirstFrame.CANCELLED
            }
            val next = OtoscopePower.afterTimeout(portOff = true, usbPresent = probe.usbPresent(), usbReady = probe.usbReady())
            if (next == OtoscopePower.Timeout.ASK_PERMISSION) {
                // Como el Panel Local: no se apaga el puerto y se pide de nuevo, con el pedido
                // de Android que trae la casilla (OtoscopePermission). El próximo start_oth lo
                // usa. El puerto se apaga al cortar la sala.
                log("[TELE] El otoscopio está enchufado pero sin permiso USB: se vuelve a pedir")
                probe.askPermissionAgain()
                return "Falta aceptar el permiso USB del otoscopio en la tablet"
            }
            otoscopePort = powerOff(otoscopePort, "otoscopio")
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
        val firstFrame = FirstFrame()
        var reason: String? = "El otoscopio no abrió en ${OPEN_TIMEOUT_MS / 1000} s"
        source.start(
            sink = { frame ->
                if (framesSent.getAndIncrement() == 0L) videoSize = "${frame.rotatedWidth}×${frame.rotatedHeight}"
                frameCapturer.pushVideoFrame(frame)
                firstFrame.arrived()
            },
            done = { result ->
                reason = result
                opened.countDown()
            },
        )
        val openDeadline = System.nanoTime() + OPEN_TIMEOUT_MS * 1_000_000L
        while (!opened.await(POLL_MS, TimeUnit.MILLISECONDS) && !cancelled() && System.nanoTime() < openDeadline) Unit
        if (cancelled()) reason = FirstFrame.CANCELLED
        reason?.let {
            releaseOtoscope()
            return it
        }
        // Una pista sin cuadros le llega al médico como video negro: se publica recién con imagen,
        // que llega cuando alguien elige «Computer» en la pantalla del otoscopio.
        val deadline = System.nanoTime() + FIRST_FRAME_TIMEOUT_MS * 1_000_000L
        while (true) {
            val arrived = firstFrame.await(POLL_MS)
            val now = probe.usbDevice()?.deviceId
            val uvc = source as? UvcOtoscopeFrames
            val reconnected = uvc != null && OtoscopeReconnect.shouldReopen(uvc.openedId, now)
            when (FirstFrame.next(arrived, cancelled(), System.nanoTime(), deadline, reconnected)) {
                FirstFrame.Step.PUBLISH -> break
                FirstFrame.Step.WAIT -> Unit
                FirstFrame.Step.REOPEN -> if (uvc != null && now != null) uvc.reopen(now)
                FirstFrame.Step.CANCELLED -> {
                    releaseOtoscope()
                    return FirstFrame.CANCELLED
                }
                FirstFrame.Step.NO_IMAGE -> {
                    log("[TELE] El otoscopio ${source.label} abrió pero no mandó ningún cuadro en ${FIRST_FRAME_TIMEOUT_MS / 1000} s: no se publica")
                    releaseOtoscope()
                    return FirstFrame.NO_IMAGE
                }
            }
        }
        log("[TELE] Llegó la imagen del otoscopio ${source.label}: se publica")
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

    /** Espera el otoscopio con la misma regla que el Panel Local. null si no apareció o se cortó. */
    private fun waitForOtoscope(cancelled: () -> Boolean): OtoscopePower.Path? {
        val power = OtoscopePower(OTOSCOPE_WAIT_NANOS, CAMERA2_GRACE_NANOS)
        // El puerto ya lo manejó powerFor: acá sólo se espera.
        power.switchOn(hubPresent = false, now = System.nanoTime())
        while (!cancelled()) {
            when (val poll = power.poll(System.nanoTime(), probe.cameraSeen(), probe.usbReady())) {
                OtoscopePower.Poll.Waiting -> Thread.sleep(POLL_MS)
                OtoscopePower.Poll.Cancelled -> return null
                is OtoscopePower.Poll.Open -> return poll.path
                is OtoscopePower.Poll.TimedOut -> return null
            }
        }
        return null
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
        otoscopePort = powerOff(otoscopePort, "otoscopio")
    }

    private fun publishStethoscope(): String? {
        val lk = room ?: return "No hay sala"
        val adm = module ?: return "No hay módulo de audio"
        val cfg = config()
        stethoPort = cfg.ykushStethoscopePort
        powerFor(cfg.ykushStethoscopePort, cfg.ykushOtoscopePort, "estetoscopio")
        // Encender el puerto es enchufar el adaptador, y apagar el del otoscopio le devuelve
        // su lugar de placa de audio: Android tarda un momento en verlo.
        var input = stethoInput(cfg)
        val deadline = System.nanoTime() + STETHO_WAIT_NANOS
        while (input == null && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS)
            input = stethoInput(cfg)
        }
        if (input == null) {
            stethoPort = powerOff(stethoPort, "estetoscopio")
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
        stethoPort = powerOff(stethoPort, "estetoscopio")
    }

    /**
     * Un solo equipo de audio USB a la vez ([InstrumentPorts]): apaga el puerto
     * del otro instrumento si el hub no lo lee apagado (aunque no lo haya
     * encendido la teleconsulta) y enciende el de [use].
     */
    private fun powerFor(use: Int, other: Int, what: String) {
        if (!ykush.plugged()) return
        val steps = InstrumentPorts.forUse(use, other, config().ykushThermometerPort, ykush.readAll())
        for (port in steps.off) {
            if (ykush.setPort(port, false)) log("[TELE] Se apagó el puerto $port antes de encender el $what: un solo equipo de audio USB a la vez")
        }
        val port = steps.on ?: return
        if (ykush.setPort(port, true)) {
            log("[TELE] Puerto $port del $what encendido")
        } else {
            log("[TELE] El YKUSH3 no encendió el puerto $port del $what: se espera igual")
        }
    }

    /** Apaga [port] si hay uno. Devuelve null, para olvidarlo en la misma línea. */
    private fun powerOff(port: Int?, what: String): Int? {
        if (port == null || !ykush.plugged()) return null
        if (ykush.setPort(port, false)) log("[TELE] Se apagó el puerto $port del $what")
        return null
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
        /** Saca el token de un mensaje de error (la dirección del websocket lo lleva como `access_token`). */
        fun redact(text: String, token: String): String =
            text.replace(token, "…").replace(Regex("access_token=[^&\\s]+"), "access_token=…")

        const val OTOSCOPE_TRACK = "otoscopio"
        const val STETHO_TRACK = "estetoscopio"
        private const val SAMPLE_RATE = 48_000
        private const val MAX_FPS = 30
        private const val VIDEO_BITRATE = 1_500_000
        private const val AUDIO_BITRATE = 64_000
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val CLOSE_TIMEOUT_MS = 5_000L
        /** Hay que dar tiempo a mirar el otoscopio, prenderlo y elegir «Computer» en su pantalla. */
        private const val FIRST_FRAME_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 250L
        private const val OTOSCOPE_WAIT_NANOS = 8L * 1_000_000_000L
        private const val CAMERA2_GRACE_NANOS = 2L * 1_000_000_000L
        private const val STETHO_WAIT_NANOS = 5L * 1_000_000_000L
    }
}
