// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.usb.DeviceInventory

/**
 * El estetoscopio del Panel Local: captura del adaptador USB, uno de los
 * filtros de [StethoFilter], el volumen, y lo reproduce por el auricular
 * H390. La onda va a un canal del [LiveFeed] para que la pantalla la dibuje
 * como las de la plaqueta. Graba hasta 15 s de lo que suena y reproduce esas
 * grabaciones por el auricular, todo en memoria: no guarda nada en disco.
 *
 * Todo el audio de la escucha pasa por un hilo propio (`berry-stetho`), que
 * es el único que toca el `AudioRecord` y su `AudioTrack`: los abre, los usa
 * y los suelta al salir, así al parar no queda ninguno abierto. La
 * reproducción de una grabación tiene su hilo y su `AudioTrack`, y nunca
 * convive con la escucha: el adaptador y el auricular no se abren dos veces.
 */
class StethoscopeLink(
    context: Context,
    private val wave: LiveFeed.Channel,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    // Sólo con [lock].
    private var worker: Thread? = null
    private var session: Session? = null

    /**
     * Una escucha. Cada hilo de audio tiene la suya: si un hilo viejo queda
     * trabado en una lectura y vuelve tarde, no sigue andando ni pisa el
     * estado de la escucha que arrancó después.
     */
    private class Session {
        @Volatile var keepRunning = true
    }

    /** Si está capturando. */
    @Volatile var running: Boolean = false
        private set

    /** Si además suena por el auricular. Sin auricular sólo se ve la onda. */
    @Volatile var playing: Boolean = false
        private set

    /** RMS del último bloque, de 0 a 1. */
    @Volatile var level: Float = 0f
        private set

    /** El filtro de la escucha; el hilo de audio lo cambia entre un bloque y el siguiente, sin cortar. */
    @Volatile var filter: StethoFilter = StethoFilter.DEFAULT

    /** De 0 a 10 (ver [StethoDsp.volumeGain]); se cambia en vivo desde el panel. */
    @Volatile var volume: Int = StethoDsp.DEFAULT_VOLUME
        set(value) {
            field = value.coerceIn(StethoDsp.VOLUME_MIN, StethoDsp.VOLUME_MAX)
        }

    // La grabación en curso y las terminadas. Las toca el hilo de audio en cada
    // bloque y la pantalla al grabar o parar: siempre con [recordLock].
    private val recordLock = Any()
    private var recorder: StethoRecorder? = null
    private var recordFilter = StethoFilter.DEFAULT
    private var recordStartedAt = 0L
    private val recordings = StethoRecordings()

    /** Los segundos que lleva la grabación en curso, o null si no está grabando. */
    val recording: Double?
        get() = synchronized(recordLock) { recorder?.seconds }

    // La reproducción de una grabación, con [playLock].
    private val playLock = Any()
    private var player: Player? = null

    /** Una reproducción, con su propio permiso para seguir, como [Session]. */
    private class Player {
        @Volatile var keepPlaying = true
        var thread: Thread? = null
    }

    /** El número de la grabación que está sonando, o null. */
    @Volatile var replaying: Int? = null
        private set

    data class Devices(val input: AudioDeviceInfo?, val output: AudioDeviceInfo?)

    /** El adaptador y el auricular según los nombres de la configuración, o null si no están. */
    fun devices(config: BerryConfig): Devices {
        val all = try {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (_: Exception) {
            return Devices(null, null)
        }
        val described = all.map { info ->
            AudioPick.Device(
                id = info.id,
                type = info.type,
                name = info.productName?.toString().orEmpty(),
                isSource = info.isSource,
                isSink = info.isSink,
                usb = DeviceInventory.isUsb(info.type),
            )
        }
        val input = AudioPick.stethoInput(described, config.stethoInputName, config.headsetName)
        val output = AudioPick.headsetOutput(described, config.headsetName)
        return Devices(
            input = input?.let { pick -> all.firstOrNull { it.id == pick.id && it.isSource } },
            output = output?.let { pick -> all.firstOrNull { it.id == pick.id && it.isSink } },
        )
    }

    /**
     * Empieza a escuchar. Devuelve el motivo si no puede: sin permiso de
     * micrófono o sin el adaptador. Sin auricular captura igual y muestra la
     * onda, pero no reproduce: por el parlante de la tablet se acoplaría con
     * el estetoscopio. Eso se ve en [playing].
     */
    fun start(config: BerryConfig): String? {
        // Una grabación sonando usa el auricular: se corta antes de abrir la escucha.
        stopPlayback()
        return synchronized(lock) { open(config) }
    }

    /** Con [lock]. */
    private fun open(config: BerryConfig): String? {
        if (running) return null
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return "Sin permiso para el micrófono."
        val (input, output) = devices(config)
        if (input == null) return "No aparece el adaptador del estetoscopio (\"${config.stethoInputName}\")."
        val record = openRecord(input) ?: return "No se pudo abrir el adaptador del estetoscopio."
        val track = output?.let(::openTrack)
        val current = Session()
        session = current
        running = true
        playing = track != null
        level = 0f
        wave.clear()
        worker = Thread({ loop(record, track, current) }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
        log("[ESTETO] Escuchando por ${input.productName}${if (track != null) ", suena por ${output.productName}" else ", sin auricular"}")
        return null
    }

    /**
     * Para y espera a que el hilo suelte el audio. Un bloque es de 20 ms,
     * así que la espera es corta aunque se llame desde la pantalla.
     *
     * Si el adaptador desapareció, la lectura puede no volver nunca y el hilo
     * no termina. Ahí se lo da por detenido igual: si no, [running] quedaría
     * en true y Escuchar diría "Ya está escuchando." sin sonido. Ese hilo
     * suelta lo suyo cuando la lectura vuelva, sin tocar una escucha nueva.
     */
    fun stop() {
        // Parar en medio de una grabación la guarda con lo que tenga.
        stopRecording()
        val (current, thread) = synchronized(lock) {
            if (!running) return
            session?.keepRunning = false
            session to worker
        }
        try {
            thread?.join(JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            // Si el hilo terminó, ya limpió él y la sesión no es más ésta.
            if (session === current) {
                log("[ESTETO] El hilo de audio no terminó a tiempo: se da por detenido")
                finish()
            }
        }
        log("[ESTETO] Detenido")
    }

    /** Deja todo en reposo. Con [lock]. */
    private fun finish() {
        session = null
        worker = null
        level = 0f
        playing = false
        running = false
    }

    /**
     * UNPROCESSED si la tablet lo ofrece; si no, VOICE_RECOGNITION. Las dos
     * vienen sin cancelación de eco ni control automático de ganancia, que
     * aplastan el sonido cardíaco. El dispositivo se elige a mano.
     */
    private fun openRecord(input: AudioDeviceInfo): AudioRecord? {
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        return try {
            @Suppress("MissingPermission") // Se chequea en start().
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes, BLOCK_SAMPLES * 2 * 4))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.preferredDevice = input
            record
        } catch (error: Exception) {
            log("[ESTETO] No se pudo abrir la captura: $error")
            null
        }
    }

    /** Streaming a 48 kHz mono, como música: sin el procesamiento de una llamada. */
    private fun openTrack(output: AudioDeviceInfo): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Unos 80 ms: alcanza para que no corte y la latencia sigue sirviendo para auscultar.
                .setBufferSizeInBytes(maxOf(minBytes, BLOCK_SAMPLES * 2 * 4))
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track.preferredDevice = output
            track
        } catch (error: Exception) {
            log("[ESTETO] No se pudo abrir el auricular: $error")
            null
        }
    }

    /**
     * Empieza a grabar lo que suena, desde el próximo bloque. Sólo mientras
     * se escucha y si no está grabando ya; devuelve si empezó.
     */
    fun startRecording(): Boolean {
        if (!running) return false
        synchronized(recordLock) {
            if (recorder != null) return false
            recorder = StethoRecorder()
            recordFilter = filter
            recordStartedAt = System.currentTimeMillis()
        }
        log("[ESTETO] Grabando")
        return true
    }

    /**
     * Corta la grabación en curso y la agrega a la lista si pasó de 1 s; si
     * no, la descarta. Devuelve la que guardó, o null. El hilo de audio la
     * cierra solo al llegar a los 15 s.
     */
    fun stopRecording(): StethoRecording? = synchronized(recordLock) { finishRecording() }

    /** Con [recordLock]. */
    private fun finishRecording(): StethoRecording? {
        val current = recorder ?: return null
        recorder = null
        val seconds = current.seconds
        val samples = current.take()
        if (seconds < StethoRecorder.MIN_SECONDS) {
            log("[ESTETO] Grabación de ${"%.1f".format(seconds)} s: muy corta, se descarta")
            return null
        }
        val saved = recordings.add(recordFilter, recordStartedAt, samples)
        log("[ESTETO] Grabación ${saved.number} guardada en memoria: ${"%.1f".format(seconds)} s")
        return saved
    }

    /** Las grabaciones que se ven, de la más vieja a la más nueva. */
    fun recordings(): List<StethoRecording> = synchronized(recordLock) { recordings.latest() }

    /** Al finalizar o bloquear el panel: corta la reproducción y suelta todas las grabaciones, también la que estaba en curso. */
    fun clearRecordings() {
        stopPlayback()
        synchronized(recordLock) {
            recorder?.take()
            recorder = null
            recordings.clear()
        }
    }

    /**
     * Reproduce una grabación por el auricular, con un `AudioTrack` propio.
     * Devuelve el motivo si no puede. La escucha tiene que estar parada (la
     * para el runtime antes): si no, las dos irían al mismo auricular. Sin
     * auricular no suena: por el parlante de la tablet se acopla con el
     * estetoscopio.
     */
    fun play(number: Int, config: BerryConfig): String? {
        stopPlayback()
        if (running) return "Primero hay que parar la escucha."
        val recording = synchronized(recordLock) { recordings.find(number) } ?: return "Esa grabación ya no está."
        val output = devices(config).output
            ?: return "Sin auricular: las grabaciones no se reproducen por el parlante de la tablet."
        val track = openTrack(output) ?: return "No se pudo abrir el auricular."
        val current = Player()
        synchronized(playLock) {
            player = current
            replaying = number
        }
        current.thread = Thread({ playLoop(track, recording.samples, current) }, PLAY_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
        log("[ESTETO] Reproduciendo la grabación $number por ${output.productName}")
        return null
    }

    /** Corta la reproducción, si hay, y espera a que su hilo suelte el auricular. */
    fun stopPlayback() {
        val current = synchronized(playLock) { player } ?: return
        current.keepPlaying = false
        try {
            current.thread?.join(JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(playLock) {
            if (player === current) {
                player = null
                replaying = null
            }
        }
    }

    private fun playLoop(track: AudioTrack, samples: ShortArray, current: Player) {
        try {
            track.play()
            var offset = 0
            while (current.keepPlaying && offset < samples.size) {
                val count = minOf(BLOCK_SAMPLES, samples.size - offset)
                val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    log("[ESTETO] El auricular devolvió $written al reproducir: se corta")
                    break
                }
                offset += written
            }
            // Lo último escrito todavía está en el buffer del auricular: se espera a que suene,
            // con un tope por si el auricular se desenchufó y la posición no avanza más.
            val deadline = System.nanoTime() + END_WAIT_NANOS
            while (current.keepPlaying && track.playbackHeadPosition < offset && System.nanoTime() < deadline) {
                Thread.sleep(END_POLL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log("[ESTETO] Error al reproducir: $error")
        } finally {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
            synchronized(playLock) {
                if (player === current) {
                    player = null
                    replaying = null
                }
            }
        }
    }

    private fun loop(record: AudioRecord, track: AudioTrack?, current: Session) {
        var band = filter
        var bandPass = band.bandPass()
        val input = ShortArray(BLOCK_SAMPLES)
        val output = ShortArray(BLOCK_SAMPLES)
        val decimated = IntArray(BLOCK_SAMPLES / StethoDsp.DECIMATION + 1)
        try {
            record.startRecording()
            track?.play()
            while (current.keepRunning) {
                val n = record.read(input, 0, input.size)
                if (n < 0) {
                    log("[ESTETO] La captura devolvió $n: se corta")
                    break
                }
                if (n == 0) continue
                // Otro filtro: uno nuevo, sin la memoria del anterior, desde este bloque.
                val wanted = filter
                if (wanted != band) {
                    band = wanted
                    bandPass = band.bandPass()
                    log("[ESTETO] Filtro ${band.lowHz.toInt()} a ${band.highHz.toInt()} Hz")
                }
                val gain = StethoDsp.volumeGain(volume)
                for (i in 0 until n) {
                    output[i] = StethoDsp.toPcm(StethoDsp.amplify(bandPass.process(input[i].toDouble()), gain))
                }
                // Sin bloquear: dos placas USB tienen relojes distintos y, si el auricular
                // va un poco más lento, esperar frenaría la captura. Se pierde un pedacito.
                track?.write(output, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                val count = StethoDsp.decimate(output, n, decimated)
                for (i in 0 until count) wave.push(decimated[i] + StethoDsp.WAVE_CENTER)
                level = StethoDsp.rms(output, n)
                // Se graba lo mismo que suena; a los 15 s la grabación se cierra sola.
                synchronized(recordLock) {
                    val recording = recorder
                    if (recording != null) {
                        recording.append(output, n)
                        if (recording.isFull) finishRecording()
                    }
                }
            }
        } catch (error: Exception) {
            log("[ESTETO] Error en la captura: $error")
        } finally {
            // Si la captura se cortó sola, lo grabado hasta ahí se guarda igual.
            stopRecording()
            release(record, track)
            // Si stop() ya lo dio por detenido, puede haber otra escucha andando: no se toca.
            synchronized(lock) { if (session === current) finish() }
        }
    }

    private fun release(record: AudioRecord, track: AudioTrack?) {
        try {
            record.stop()
        } catch (_: Exception) {
        }
        record.release()
        if (track != null) {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = BandPass.SAMPLE_RATE

        /** 20 ms a 48 kHz: sale un bloque al auricular cada 20 ms. */
        const val BLOCK_SAMPLES = 960
        const val THREAD_NAME = "berry-stetho"
        const val PLAY_THREAD_NAME = "berry-stetho-play"
        private const val JOIN_MS = 500L
        private const val END_POLL_MS = 20L
        private const val END_WAIT_NANOS = 1_000_000_000L
    }
}
