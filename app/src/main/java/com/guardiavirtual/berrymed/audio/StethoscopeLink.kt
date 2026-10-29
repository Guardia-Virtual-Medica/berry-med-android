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
 * El estetoscopio del Panel Local: captura del adaptador USB, filtro de 20 a
 * 500 Hz, ganancia, y lo reproduce por el auricular H390. La onda va a un
 * canal del [LiveFeed] para que la pantalla la dibuje como las de la plaqueta.
 * No graba ni guarda nada.
 *
 * Todo el audio pasa por un hilo propio (`berry-stetho`), que es el único que
 * toca el `AudioRecord` y el `AudioTrack`: los abre, los usa y los suelta al
 * salir, así al parar no queda ninguno abierto.
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

    /** x1, x2 o x4; se cambia en vivo desde la tarjeta. */
    @Volatile var gain: Int = StethoDsp.GAINS.first()

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
    fun start(config: BerryConfig): String? = synchronized(lock) {
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
        null
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

    private fun loop(record: AudioRecord, track: AudioTrack?, current: Session) {
        val filter = BandPass()
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
                val currentGain = gain
                for (i in 0 until n) {
                    output[i] = StethoDsp.toPcm(StethoDsp.amplify(filter.process(input[i].toDouble()), currentGain))
                }
                // Sin bloquear: dos placas USB tienen relojes distintos y, si el auricular
                // va un poco más lento, esperar frenaría la captura. Se pierde un pedacito.
                track?.write(output, 0, n, AudioTrack.WRITE_NON_BLOCKING)
                val count = StethoDsp.decimate(output, n, decimated)
                for (i in 0 until count) wave.push(decimated[i] + StethoDsp.WAVE_CENTER)
                level = StethoDsp.rms(output, n)
            }
        } catch (error: Exception) {
            log("[ESTETO] Error en la captura: $error")
        } finally {
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
        private const val JOIN_MS = 500L
    }
}
