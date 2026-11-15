// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.usb.DeviceInventory

/**
 * Fija por dónde sale la videollamada del kiosco. La llamada del `WebView`
 * corre en este proceso y se reproduce como `USAGE_MEDIA`, en `MODE_NORMAL`:
 * ahí manda Android (el último equipo enchufado) y elegir un dispositivo no
 * cambia nada. Por eso, con el kiosco adelante, Berry Med pone
 * `MODE_IN_COMMUNICATION` (la reproducción de medios sigue entonces al
 * dispositivo de comunicación) y fija el elegido ([AudioPick.callOutput]):
 * con Altavoz, el parlante siempre; con Auricular, el jack y, si no hay
 * nada, el parlante. Al dejar el kiosco suelta el dispositivo y
 * vuelve a `MODE_NORMAL`: en el Panel Local el estetoscopio elige el jack por
 * su cuenta, en modo normal.
 *
 * Android puede devolver el modo a normal y Chromium fijar otro dispositivo
 * por su cuenta: los dos se reafirman con el freno de [CallReassert].
 *
 * [TAKE_CALL_MODE] en false vuelve al comportamiento anterior al plan 18:
 * Berry Med no toca el modo y fija el dispositivo sólo si otro puso el modo
 * comunicación.
 *
 * Todo pasa en el hilo principal: los avisos de Android y las reafirmaciones
 * diferidas. `setCommunicationDevice` existe desde Android 12; en menos sólo
 * queda en el log.
 */
class CallAudioRouter(
    context: Context,
    private val config: () -> BerryConfig,
    private val log: (String) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val reassert = CallReassert()
    private val modeReassert = CallReassert()
    private val recheck = Runnable { if (Build.VERSION.SDK_INT >= 31) check() }

    /** Si el kiosco está adelante. Sólo en el hilo principal. */
    private var kiosk = false

    /** Si el modo comunicación de ahora lo puso Berry Med. Lo lee Testing. */
    @Volatile private var modeByUs = false

    /** Lo último que se avisó en el log, para no repetirlo en cada vuelta. */
    private var announced: String? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 31) {
            log("[LLAMADA] Android ${Build.VERSION.RELEASE}: no se puede fijar la salida de la llamada")
            return
        }
        listen()
        refresh()
    }

    /**
     * El kiosco quedó adelante o dejó de estarlo (también al abrir el Panel
     * Local, que no espera al `onStop` del kiosco para soltar el modo).
     */
    fun setKioskVisible(visible: Boolean) {
        handler.post {
            if (kiosk == visible) return@post
            kiosk = visible
            reassert.reset()
            modeReassert.reset()
            if (Build.VERSION.SDK_INT >= 31) check()
        }
    }

    /** Cambió la salida elegida: si hay una llamada, se mueve ahora. */
    fun refresh() {
        if (Build.VERSION.SDK_INT < 31) return
        handler.post {
            reassert.reset()
            check()
        }
    }

    /** El modo, quién lo puso, la salida elegida y el dispositivo actual, para Testing. */
    fun describe(): String {
        val cfg = config()
        val chosen = if (cfg.callAudioOutput == CALL_AUDIO_SPEAKER) "parlante de la tablet" else "auriculares del jack"
        if (Build.VERSION.SDK_INT < 31) return "Llamada: sale por $chosen · Android no deja fijarla"
        val mode = when {
            audio.mode != AudioManager.MODE_IN_COMMUNICATION -> "modo normal"
            modeByUs -> "modo comunicación, lo puso Berry Med con el kiosco adelante"
            else -> "modo comunicación, lo puso otro (Chromium)"
        }
        val wanted = pick(cfg)
        return buildString {
            append("Llamada: sale por $chosen · $mode · dispositivo de comunicación: ")
            append(audio.communicationDevice?.let(::label) ?: "ninguno")
            if (!TAKE_CALL_MODE) append("\nBerry Med no toca el modo (CallAudioRouter.TAKE_CALL_MODE en false)")
            if (cfg.callAudioOutput != CALL_AUDIO_SPEAKER && wanted?.type == AudioPick.TYPE_BUILTIN_SPEAKER) {
                append("\n$FALLBACK_LINE")
            }
            append("\n${describeRecordings()}")
        }
    }

    /**
     * Qué graba este proceso y desde qué entrada: la llamada del `WebView` y,
     * si está publicando, el estetoscopio de LiveKit. Android sólo informa las
     * grabaciones propias, y las dos son de Berry Med.
     */
    private fun describeRecordings(): String {
        val recordings = try {
            audio.activeRecordingConfigurations
        } catch (_: Exception) {
            return "Entradas grabando: no se pudo leer"
        }
        if (recordings.isEmpty()) return "Entradas grabando: ninguna"
        return recordings.joinToString(prefix = "Entradas grabando: ", separator = "; ") { recording ->
            val device = recording.audioDevice?.let { info ->
                if (AudioPick.isJack(info.type)) "el jack de la tablet"
                else info.productName?.toString().orEmpty().ifBlank { "tipo ${info.type}" }
            } ?: "entrada desconocida"
            "${sourceName(recording.clientAudioSource)} por $device"
        }
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION (la llamada)"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> "fuente $source"
    }

    @RequiresApi(31)
    private fun listen() {
        audio.addOnModeChangedListener(mainExecutor) { check() }
        audio.addOnCommunicationDeviceChangedListener(mainExecutor) { check() }
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = check()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = check()
            },
            handler,
        )
    }

    @RequiresApi(31)
    private fun pick(cfg: BerryConfig): AudioDeviceInfo? {
        val devices = audio.availableCommunicationDevices
        val described = devices.map { info ->
            AudioPick.Device(
                id = info.id,
                type = info.type,
                name = info.productName?.toString().orEmpty(),
                isSource = info.isSource,
                isSink = info.isSink,
                usb = DeviceInventory.isUsb(info.type),
            )
        }
        val chosen = AudioPick.callOutput(described, cfg.callAudioOutput) ?: return null
        return devices.firstOrNull { it.id == chosen.id }
    }

    /** Al entrar, primero el modo y después el dispositivo; al salir, al revés. */
    @RequiresApi(31)
    private fun check() {
        handler.removeCallbacks(recheck)
        if (TAKE_CALL_MODE && kiosk) checkMode()
        checkDevice()
        if (TAKE_CALL_MODE && !kiosk) checkMode()
    }

    /** Con el kiosco adelante, `MODE_IN_COMMUNICATION`; si no, `MODE_NORMAL`. */
    @RequiresApi(31)
    private fun checkMode() {
        val current = audio.mode
        if (!kiosk) {
            modeReassert.next(System.nanoTime(), inCall = false, wanted = null, current = null)
            modeByUs = false
            // Aunque lo haya puesto Chromium: el Panel Local escucha el estetoscopio en modo normal.
            if (current != AudioManager.MODE_NORMAL) {
                audio.mode = AudioManager.MODE_NORMAL
                log("[LLAMADA] El kiosco dejó de estar adelante: modo normal")
            }
            return
        }
        when (val step = modeReassert.next(System.nanoTime(), true, AudioManager.MODE_IN_COMMUNICATION, current)) {
            CallReassert.Step.Nothing, CallReassert.Step.Clear -> Unit
            is CallReassert.Step.Wait -> handler.postDelayed(recheck, step.nanos / NANOS_PER_MS + 1)
            CallReassert.Step.GiveUp -> log(
                "[LLAMADA] Android no deja el modo comunicación después de ${CallReassert.MAX_TRIES} intentos: se deja de insistir",
            )
            CallReassert.Step.Set -> {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                modeReassert.applied()
                modeByUs = true
                log("[LLAMADA] El kiosco está adelante: Berry Med pone el modo comunicación")
                handler.postDelayed(recheck, CallReassert.MIN_GAP_NANOS / NANOS_PER_MS + 1)
            }
        }
    }

    @RequiresApi(31)
    private fun checkDevice() {
        val inCall = if (TAKE_CALL_MODE) kiosk else audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val cfg = config()
        if (!inCall) announced = null
        // Fuera de la llamada ni se pregunta qué dispositivo hay.
        val wanted = if (inCall) pick(cfg) else null
        val current = if (inCall) audio.communicationDevice else null
        when (val step = reassert.next(System.nanoTime(), inCall, wanted?.id, current?.id)) {
            CallReassert.Step.Nothing -> Unit
            is CallReassert.Step.Wait -> handler.postDelayed(recheck, step.nanos / NANOS_PER_MS + 1)
            CallReassert.Step.GiveUp -> log(
                "[LLAMADA] No queda en ${wanted?.let(::label)} después de ${CallReassert.MAX_TRIES} intentos: se deja de insistir",
            )
            CallReassert.Step.Clear -> {
                audio.clearCommunicationDevice()
                log("[LLAMADA] Terminó la llamada: se suelta el dispositivo")
            }
            CallReassert.Step.Set -> {
                val device = wanted ?: return
                announce(cfg, device)
                if (audio.setCommunicationDevice(device)) {
                    reassert.applied()
                } else {
                    log("[LLAMADA] Android no aceptó ${label(device)} como dispositivo de la llamada")
                }
                // Si no quedó, no llega ningún aviso: se mira de nuevo.
                handler.postDelayed(recheck, CallReassert.MIN_GAP_NANOS / NANOS_PER_MS + 1)
            }
        }
    }

    @RequiresApi(31)
    private fun announce(cfg: BerryConfig, device: AudioDeviceInfo) {
        val fallback = cfg.callAudioOutput != CALL_AUDIO_SPEAKER && device.type == AudioPick.TYPE_BUILTIN_SPEAKER
        val line = if (fallback) {
            "[LLAMADA] $FALLBACK_LINE"
        } else {
            "[LLAMADA] La llamada sale por ${label(device)}"
        }
        if (line == announced) return
        announced = line
        log(line)
    }

    // Los auriculares del jack suelen venir sin nombre de producto: se dicen por el tipo.
    private fun label(device: AudioDeviceInfo): String = when {
        device.type == AudioPick.TYPE_BUILTIN_SPEAKER -> "el parlante"
        AudioPick.isJack(device.type) -> "el jack de la tablet"
        else -> device.productName?.toString().orEmpty().ifBlank { "tipo ${device.type}" }
    }

    companion object {
        /**
         * El interruptor del plan 18: true, Berry Med pone el modo
         * comunicación con el kiosco adelante. false vuelve al de antes (no
         * toca el modo; en la tablet el selector no hacía nada).
         */
        const val TAKE_CALL_MODE = true

        private const val NANOS_PER_MS = 1_000_000L
        private const val FALLBACK_LINE = "Nada en el jack: la llamada sale por el parlante"
    }
}
