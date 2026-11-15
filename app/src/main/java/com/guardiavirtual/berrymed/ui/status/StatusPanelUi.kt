// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.net.WifiState
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.service.StatusSnapshot
import com.guardiavirtual.berrymed.ui.panel.PanelUi

/**
 * El color de un estado del panel lateral. OK es verde (punto lleno), WARN
 * ámbar y BAD rojo (aro hueco y fila con fondo): el rojo acá no es una alarma
 * clínica, es un instrumento para resolver antes de atender.
 */
enum class StatusTone { OK, WARN, BAD }

/** Una fila de Dispositivos: "Otoscopio", su tono y "Listo". */
data class DeviceRow(val name: String, val tone: StatusTone, val text: String)

/** El ícono del Wi-Fi: con señal, con una barra o sin red. */
enum class WifiIcon { WIFI, WEAK, OFF }

/**
 * La cabecera: [name] es el SSID, «Wi-Fi» sin el permiso de ubicación o
 * «Sin Wi-Fi»; [bars] de 0 a 4; [offline] es sin red, y entonces la cabecera
 * abre el panel de Wi-Fi de Android.
 */
data class WifiRow(
    val icon: WifiIcon,
    val name: String,
    val text: String,
    val tone: StatusTone,
    val bars: Int,
    val offline: Boolean,
)

/** Por dónde va la llamada, como se muestra: el auricular del jack o la tablet. */
enum class CallRoute { HEADSET, SPEAKER }

/** En qué pantalla está el panel: el kiosco, el Panel Local o su pantalla de PIN. */
enum class PanelMode { REMOTE, LOCAL, LOCAL_LOCKED }

/** Todo lo que dibuja el panel lateral en un refresco. */
data class StatusPanelState(
    val wifi: WifiRow,
    val mode: PanelMode,
    /** El texto del botón bloqueado; null si se puede cambiar de modo. */
    val modeBlocked: String?,
    /** Las siete filas, en el orden fijo. */
    val devices: List<DeviceRow>,
    /** Cuántas filas están en verde, sobre [devices]. */
    val okCount: Int,
    val countTone: StatusTone,
    val jackPresent: Boolean,
    val callRoute: CallRoute,
    val callHelp: String,
)

/**
 * El panel lateral sin Android: dado lo que lee el runtime, dice qué mostrar.
 * Muestra, no decide: no enciende ni cambia nada.
 */
object StatusPanelUi {
    const val CONNECTED = "Conectado"
    const val CONNECTING = "Conectando"
    const val DISCONNECTED = "Desconectado"
    const val READY = "Listo"
    const val LIVE = "En vivo"
    const val NO_HUB = "Sin hub"
    const val NO_USB_PERMISSION = "Sin permiso USB"

    fun build(s: StatusSnapshot, mode: PanelMode): StatusPanelState {
        val rows = devices(s, localScreen = mode != PanelMode.REMOTE)
        val route = callRoute(s.callAudioChoice, s.jackPresent)
        return StatusPanelState(
            wifi = wifi(s.wifi),
            mode = mode,
            // Volver a ONDOCTOR365 desde el Panel Local siempre se puede: el panel no deja empezar una consulta.
            modeBlocked = if (mode == PanelMode.REMOTE) StatusSnapshot.modeSwitchBlock(s.sessionActive, s.teleInRoom) else null,
            devices = rows,
            okCount = rows.count { it.tone == StatusTone.OK },
            countTone = countTone(rows),
            jackPresent = s.jackPresent,
            callRoute = route,
            callHelp = callHelp(route, s.jackPresent),
        )
    }

    /** «Sin Wi-Fi» sin transporte Wi-Fi, «Sin internet» sin validar, «Señal débil» con una barra. */
    fun wifi(w: WifiState): WifiRow {
        if (!w.connected) {
            return WifiRow(WifiIcon.OFF, "Sin Wi-Fi", "Tocá para elegir una red", StatusTone.BAD, bars = 0, offline = true)
        }
        val name = w.ssid ?: "Wi-Fi"
        val bars = w.level
        return when {
            !w.validated -> WifiRow(WifiIcon.WIFI, name, "Sin internet", StatusTone.WARN, bars, offline = false)
            bars == 1 -> WifiRow(WifiIcon.WEAK, name, "Señal débil", StatusTone.WARN, bars, offline = false)
            else -> WifiRow(WifiIcon.WIFI, name, "Señal buena", StatusTone.OK, bars, offline = false)
        }
    }

    /**
     * Las siete filas: SpO2, ECG, Presión, Temperatura, Otoscopio, Estetoscopio
     * y Hub USB. [localScreen] es estar en el Panel Local, también en su
     * pantalla de PIN, donde el modo local todavía no empezó.
     */
    fun devices(s: StatusSnapshot, localScreen: Boolean = s.localMode): List<DeviceRow> {
        val local = s.local
        val berry = when {
            PanelUi.berryLive(local) -> Pair(StatusTone.OK, CONNECTED)
            local.berryUp -> Pair(StatusTone.WARN, CONNECTING)
            else -> Pair(StatusTone.BAD, DISCONNECTED)
        }
        val ecg = if (berry.first == StatusTone.OK && PanelUi.heartNote(local) == PanelUi.LEADS_OFF) {
            Pair(StatusTone.WARN, PanelUi.LEADS_OFF)
        } else {
            berry
        }
        return listOf(
            DeviceRow("SpO2", berry.first, berry.second),
            DeviceRow("ECG", ecg.first, ecg.second),
            DeviceRow("Presión", berry.first, berry.second),
            if (local.thermoConnected) {
                DeviceRow("Temperatura", StatusTone.OK, CONNECTED)
            } else {
                DeviceRow("Temperatura", StatusTone.BAD, DISCONNECTED)
            },
            otoscope(s, localScreen),
            stethoscope(s, localScreen),
            hub(s),
        )
    }

    private fun otoscope(s: StatusSnapshot, localScreen: Boolean): DeviceRow {
        val state = s.local.otoscopeState
        val (tone, text) = when {
            state == OtoscopePower.State.ON -> Pair(StatusTone.OK, LIVE)
            state == OtoscopePower.State.POWERING_PORT || state == OtoscopePower.State.WAITING_CAMERA ->
                Pair(StatusTone.WARN, CONNECTING)
            s.local.otoscopePlugged -> Pair(StatusTone.OK, CONNECTED)
            else -> unplugged(s, localScreen)
        }
        return DeviceRow("Otoscopio", tone, text)
    }

    private fun stethoscope(s: StatusSnapshot, localScreen: Boolean): DeviceRow {
        val (tone, text) = when {
            s.local.stethoRunning -> Pair(StatusTone.OK, LIVE)
            s.local.stethoStarting -> Pair(StatusTone.WARN, CONNECTING)
            s.local.stethoPlugged -> Pair(StatusTone.OK, CONNECTED)
            else -> unplugged(s, localScreen)
        }
        return DeviceRow("Estetoscopio", tone, text)
    }

    /**
     * Otoscopio o estetoscopio sin enchufar: en Local con el YKUSH3 el puerto
     * arranca apagado y se enciende al abrirlo, así que está «Listo», no en
     * rojo. Sin YKUSH3, «Sin hub».
     */
    private fun unplugged(s: StatusSnapshot, localScreen: Boolean): Pair<StatusTone, String> = when {
        !s.ykushPlugged -> Pair(StatusTone.BAD, NO_HUB)
        localScreen || s.localMode -> Pair(StatusTone.OK, READY)
        else -> Pair(StatusTone.BAD, DISCONNECTED)
    }

    private fun hub(s: StatusSnapshot): DeviceRow = when {
        !s.ykushPlugged -> DeviceRow("Hub USB", StatusTone.BAD, DISCONNECTED)
        s.usbPermissionMissing -> DeviceRow("Hub USB", StatusTone.BAD, NO_USB_PERMISSION)
        else -> DeviceRow("Hub USB", StatusTone.OK, CONNECTED)
    }

    /** El conteo: verde con todo verde, rojo con algún rojo y ámbar si sólo hay ámbar. */
    fun countTone(rows: List<DeviceRow>): StatusTone = when {
        rows.any { it.tone == StatusTone.BAD } -> StatusTone.BAD
        rows.any { it.tone == StatusTone.WARN } -> StatusTone.WARN
        else -> StatusTone.OK
    }

    /**
     * Lo que se ve elegido: Altavoz con «parlante» o sin nada en el jack, y si
     * no Auricular. No reescribe el ajuste: cuando vuelve el auricular, vuelve solo.
     */
    fun callRoute(choice: String, jackPresent: Boolean): CallRoute =
        if (choice == CALL_AUDIO_SPEAKER || !jackPresent) CallRoute.SPEAKER else CallRoute.HEADSET

    /**
     * La ayuda debajo del segmentado de Sonido, igual en los dos modos.
     * Altavoz mueve la llamada al parlante y al micrófono de la tablet, pero el
     * estetoscopio sigue sólo en el auricular: por el parlante se acopla
     * ([com.guardiavirtual.berrymed.audio.AudioPick.stethoOutput]).
     */
    fun callHelp(route: CallRoute, jackPresent: Boolean): String = when {
        !jackPresent -> "Sin auricular en el jack: la llamada va por el altavoz y el estetoscopio no suena."
        route == CallRoute.HEADSET -> "Todo suena por el auricular."
        else -> "La llamada va por el altavoz y el micrófono de la tablet. El estetoscopio sigue en el auricular."
    }
}
