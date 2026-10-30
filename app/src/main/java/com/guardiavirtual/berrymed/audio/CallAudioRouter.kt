// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.usb.DeviceInventory

/**
 * Fija por dónde sale la videollamada del kiosco. El `WebView` corre en este
 * proceso y, con el micrófono en modo comunicación, Chromium pone
 * `MODE_IN_COMMUNICATION` y elige solo el dispositivo: agrupa todo lo USB y
 * toma el primero, sin distinguir el H390 del adaptador del estetoscopio, y
 * puede volver a elegir cuando cambian los dispositivos. Por eso se fija y se
 * reafirma. El micrófono acompaña: Android los mueve juntos.
 *
 * Fuera de la llamada sólo suelta, una vez, lo que haya fijado: no toca el modo
 * ni el foco. El estetoscopio del Panel Local no pasa por acá; sale siempre por
 * el auricular.
 *
 * Este router sólo actúa en modo comunicación, y ahí agrega la distinción entre
 * el H390 y el adaptador. En el emulador (WebView 124, Android 15) una llamada
 * WebRTC queda en `MODE_NORMAL`, captura como `CAMCORDER` y reproduce como
 * `USAGE_MEDIA`: ahí no actúa, y `setCommunicationDevice` no movería esa
 * reproducción. La elección igual llega por otro lado: los puertos en reposo
 * (`HubRestState`) dejan como salida USB sólo el H390, o ninguna con el parlante.
 * Por razonamiento, sin probar en la tablet, eso alcanza hasta que se enciende el
 * puerto del estetoscopio. Ver los riesgos de `docs/hardware.md`.
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
    private val recheck = Runnable { if (Build.VERSION.SDK_INT >= 31) check() }

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

    /** Cambió la salida elegida: si hay una llamada, se mueve ahora. */
    fun refresh() {
        if (Build.VERSION.SDK_INT < 31) return
        handler.post {
            reassert.reset()
            check()
        }
    }

    /** La salida elegida y el dispositivo actual, para Testing. */
    fun describe(): String {
        val cfg = config()
        val chosen = if (cfg.callAudioOutput == CALL_AUDIO_SPEAKER) "parlante de la tablet" else "auricular"
        if (Build.VERSION.SDK_INT < 31) return "Llamada: sale por $chosen · Android no deja fijarla"
        val inCall = audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val current = audio.communicationDevice
        val wanted = pick(cfg)
        return buildString {
            append("Llamada: sale por $chosen · dispositivo de comunicación: ")
            append(current?.let(::label) ?: "ninguno")
            append(if (inCall) " · modo comunicación" else " · modo normal (Android no ve una llamada)")
            if (cfg.callAudioOutput != CALL_AUDIO_SPEAKER && wanted?.type == AudioPick.TYPE_BUILTIN_SPEAKER) {
                append("\nEl auricular no está: la llamada sale por el parlante")
            }
        }
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
        val chosen = AudioPick.callOutput(described, cfg.callAudioOutput, cfg.headsetName) ?: return null
        return devices.firstOrNull { it.id == chosen.id }
    }

    @RequiresApi(31)
    private fun check() {
        handler.removeCallbacks(recheck)
        val inCall = audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val cfg = config()
        if (!inCall) announced = null
        // Fuera de la llamada ni se pregunta qué dispositivo hay.
        val wanted = if (inCall) pick(cfg) else null
        val current = if (inCall) audio.communicationDevice else null
        val step = reassert.next(System.nanoTime(), inCall, wanted?.id, current?.id)
        when (step) {
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
            "[LLAMADA] El auricular no está: la llamada sale por el parlante"
        } else {
            "[LLAMADA] La llamada sale por ${label(device)}"
        }
        if (line == announced) return
        announced = line
        log(line)
    }

    private fun label(device: AudioDeviceInfo): String =
        if (device.type == AudioPick.TYPE_BUILTIN_SPEAKER) "el parlante" else device.productName?.toString().orEmpty().ifBlank { "tipo ${device.type}" }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
