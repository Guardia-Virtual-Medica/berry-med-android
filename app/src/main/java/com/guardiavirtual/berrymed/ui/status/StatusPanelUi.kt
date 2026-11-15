// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.net.WifiState
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.service.AppPermission
import com.guardiavirtual.berrymed.service.StatusSnapshot
import com.guardiavirtual.berrymed.usb.UsbRole
import com.guardiavirtual.berrymed.ui.panel.PanelUi

/**
 * El color de un estado del panel lateral. OK es verde (punto lleno), WARN
 * ámbar y BAD rojo (aro hueco y fila con fondo): el rojo acá no es una alarma
 * clínica, es un instrumento para resolver antes de atender.
 */
enum class StatusTone { OK, WARN, BAD }

/**
 * Lo que hace tocar una fila del panel lateral. Sólo las filas a las que les
 * falta un permiso (y la del Wi-Fi, siempre) llevan una.
 */
enum class StatusAction {
    /** El panel de Wi-Fi de Android. */
    OPEN_WIFI,

    /** Volver a pedir, en fila, los permisos USB de la plaqueta, el hub y el termómetro. */
    USB_PERMISSION,

    /** El permiso USB del otoscopio, con el pedido de Android que trae «Usar de forma predeterminada». */
    OTOSCOPE_PERMISSION,

    /** Los permisos de la app: se piden o, si Android ya no deja, se abren los ajustes de la app. */
    CAMERA,
    MICROPHONE,
    LOCATION,
}

/** Una fila de Dispositivos: "Otoscopio", su tono, "Listo" y qué hace tocarla (null: nada). */
data class DeviceRow(val name: String, val tone: StatusTone, val text: String, val action: StatusAction? = null)

/** El ícono del Wi-Fi: con señal, con una barra o sin red. */
enum class WifiIcon { WIFI, WEAK, OFF }

/**
 * La cabecera: [name] es el SSID, «Wi-Fi conectado» sin el permiso de
 * ubicación o «Sin Wi-Fi»; [bars] de 0 a 4; [offline] es sin red. Se toca
 * siempre: [action] abre el panel de Wi-Fi de Android o, conectada sin el
 * permiso de ubicación, lo pide.
 */
data class WifiRow(
    val icon: WifiIcon,
    val name: String,
    val text: String,
    val tone: StatusTone,
    val bars: Int,
    val offline: Boolean,
    val action: StatusAction = StatusAction.OPEN_WIFI,
)

/** Por dónde va la llamada, como se muestra: el auricular del jack o la tablet. */
enum class CallRoute { HEADSET, SPEAKER }

/** En qué pantalla está el panel: el kiosco, el Panel Local, su pantalla de espera o Inicio. */
enum class PanelMode {
    REMOTE,
    LOCAL,
    LOCAL_LOCKED,

    /** Inicio: ni remoto ni local todavía. El panel no muestra Modo ni la casita. */
    HOME,
}

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
    const val WAITING_IMAGE = "Esperando imagen"
    const val NO_HUB = "Sin hub"
    const val NO_PERMISSION = "Falta permiso"
    const val WIFI_CONNECTED = "Wi-Fi conectado"

    fun build(s: StatusSnapshot, mode: PanelMode): StatusPanelState {
        val rows = devices(s)
        val route = callRoute(s.callAudioChoice, s.jackPresent)
        return StatusPanelState(
            wifi = wifi(s.wifi, locationGranted = AppPermission.LOCATION !in s.appMissing),
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

    /**
     * «Sin Wi-Fi» sin transporte Wi-Fi, «Sin internet» sin validar, «Falta
     * permiso» conectada sin la ubicación (sin ella Android no da el nombre),
     * «Señal débil» con una barra. Sin red siempre abre el panel de Wi-Fi.
     */
    fun wifi(w: WifiState, locationGranted: Boolean = true): WifiRow {
        if (!w.connected) {
            return WifiRow(WifiIcon.OFF, "Sin Wi-Fi", "Tocá para elegir una red", StatusTone.BAD, bars = 0, offline = true)
        }
        val name = w.ssid ?: WIFI_CONNECTED
        val bars = w.level
        val icon = if (bars == 1) WifiIcon.WEAK else WifiIcon.WIFI
        return when {
            !w.validated -> WifiRow(WifiIcon.WIFI, name, "Sin internet", StatusTone.WARN, bars, offline = false)
            !locationGranted ->
                WifiRow(icon, name, NO_PERMISSION, StatusTone.WARN, bars, offline = false, action = StatusAction.LOCATION)
            bars == 1 -> WifiRow(WifiIcon.WEAK, name, "Señal débil", StatusTone.WARN, bars, offline = false)
            else -> WifiRow(WifiIcon.WIFI, name, "Señal buena", StatusTone.OK, bars, offline = false)
        }
    }

    /**
     * Las siete filas: SpO2, ECG, Presión, Temperatura, Otoscopio, Estetoscopio
     * y Hub USB. Iguales en todas las pantallas.
     */
    fun devices(s: StatusSnapshot): List<DeviceRow> {
        val local = s.local
        val berry = when {
            // Enchufada sin permiso no se abre: no es lo mismo que desenchufada.
            UsbRole.BOARD in s.usbMissing -> missing()
            PanelUi.berryLive(local) -> Pair(StatusTone.OK, CONNECTED)
            local.berryUp -> Pair(StatusTone.WARN, CONNECTING)
            else -> Pair(StatusTone.BAD, DISCONNECTED)
        }
        val ecg = if (berry.first == StatusTone.OK && PanelUi.heartNote(local) == PanelUi.LEADS_OFF) {
            Pair(StatusTone.WARN, PanelUi.LEADS_OFF)
        } else {
            berry
        }
        val thermo = when {
            local.thermoConnected -> Pair(StatusTone.OK, CONNECTED)
            UsbRole.THERMOMETER in s.usbMissing -> missing()
            else -> Pair(StatusTone.BAD, DISCONNECTED)
        }
        return listOf(
            row("SpO2", berry, StatusAction.USB_PERMISSION),
            row("ECG", ecg, StatusAction.USB_PERMISSION),
            row("Presión", berry, StatusAction.USB_PERMISSION),
            row("Temperatura", thermo, StatusAction.USB_PERMISSION),
            otoscope(s),
            stethoscope(s),
            hub(s),
        )
    }

    /** Un permiso faltante: ámbar (no el rojo de desconectado) y «Falta permiso». */
    private fun missing(): Pair<StatusTone, String> = Pair(StatusTone.WARN, NO_PERMISSION)

    /** Arma la fila; si dice «Falta permiso», con la acción que lo pide. */
    private fun row(name: String, state: Pair<StatusTone, String>, action: StatusAction? = null): DeviceRow =
        DeviceRow(name, state.first, state.second, action.takeIf { state.second == NO_PERMISSION })

    /**
     * «En vivo» sólo con cuadros; abierto (en el Panel Local) o encendiéndose
     * para la teleconsulta sin imagen todavía, «Esperando imagen», como el
     * encabezado del Panel Local. Sin el permiso USB del otoscopio, o sin el
     * de la cámara de la app, no hay imagen: «Falta permiso», salvo que ya
     * esté en vivo o encendiéndose.
     */
    private fun otoscope(s: StatusSnapshot): DeviceRow {
        val state = s.local.otoscopeState
        val needs = when {
            UsbRole.OTOSCOPE in s.usbMissing -> StatusAction.OTOSCOPE_PERMISSION
            AppPermission.CAMERA in s.appMissing -> StatusAction.CAMERA
            else -> null
        }
        val pair = when {
            s.otoscopeImage -> Pair(StatusTone.OK, LIVE)
            state == OtoscopePower.State.ON || s.otoscopeWaitingImage -> Pair(StatusTone.WARN, WAITING_IMAGE)
            state == OtoscopePower.State.POWERING_PORT || state == OtoscopePower.State.WAITING_CAMERA ->
                Pair(StatusTone.WARN, CONNECTING)
            needs != null -> missing()
            s.local.otoscopePlugged -> Pair(StatusTone.OK, CONNECTED)
            else -> unplugged(s)
        }
        return row("Otoscopio", pair, needs)
    }

    /** El estetoscopio es audio USB, sin permiso USB: lo que le puede faltar es el micrófono. */
    private fun stethoscope(s: StatusSnapshot): DeviceRow {
        val pair = when {
            s.local.stethoRunning -> Pair(StatusTone.OK, LIVE)
            s.local.stethoStarting -> Pair(StatusTone.WARN, CONNECTING)
            AppPermission.MICROPHONE in s.appMissing -> missing()
            s.local.stethoPlugged -> Pair(StatusTone.OK, CONNECTED)
            else -> unplugged(s)
        }
        return row("Estetoscopio", pair, StatusAction.MICROPHONE)
    }

    /**
     * Otoscopio o estetoscopio sin enchufar: en reposo su puerto está apagado
     * (también con el kiosco) y se enciende al usarlo, así que con el YKUSH3
     * está «Listo»: el hub está y el puerto se puede encender. Enchufado no se
     * puede saber con el puerto apagado. Sin YKUSH3, «Sin hub».
     */
    private fun unplugged(s: StatusSnapshot): Pair<StatusTone, String> =
        if (s.ykushPlugged) Pair(StatusTone.OK, READY) else Pair(StatusTone.BAD, NO_HUB)

    private fun hub(s: StatusSnapshot): DeviceRow = when {
        !s.ykushPlugged -> DeviceRow("Hub USB", StatusTone.BAD, DISCONNECTED)
        UsbRole.HUB in s.usbMissing -> row("Hub USB", missing(), StatusAction.USB_PERMISSION)
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
