// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import com.guardiavirtual.berrymed.config.CALL_AUDIO_HEADSET
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.i18n.es
import com.guardiavirtual.berrymed.i18n.inLanguage
import com.guardiavirtual.berrymed.net.WifiState
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.service.LocalSnapshot
import com.guardiavirtual.berrymed.service.AppPermission
import com.guardiavirtual.berrymed.service.StatusSnapshot
import com.guardiavirtual.berrymed.usb.UsbRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusPanelUiTest {
    /** Una plaqueta transmitiendo, el termómetro y los dos instrumentos enchufados por el YKUSH3 con permiso. */
    private fun status(
        berryUp: Boolean = true,
        lastDataAge: Double? = 0.2,
        leadOff: Boolean = false,
        thermoConnected: Boolean = true,
        otoscopePlugged: Boolean = true,
        otoscopeState: OtoscopePower.State = OtoscopePower.State.OFF,
        stethoPlugged: Boolean = true,
        stethoStarting: Boolean = false,
        stethoRunning: Boolean = false,
        ykushPlugged: Boolean = true,
        usbMissing: Set<UsbRole> = emptySet(),
        appMissing: Set<AppPermission> = emptySet(),
        localMode: Boolean = false,
        sessionActive: Boolean = false,
        teleInRoom: Boolean = false,
        callAudioChoice: String = CALL_AUDIO_HEADSET,
        jackPresent: Boolean = true,
        wifi: WifiState = WifiState(connected = true, validated = true, ssid = "Consultorio-5G", rssi = -55),
    ) = StatusSnapshot(
        local = LocalSnapshot(
            berryUp = berryUp,
            lastDataAge = lastDataAge,
            vitals = VitalSigns(heartRate = "72"),
            ecgInfo = EcgInfo(leadOff = leadOff, weakSignal = false),
            thermoConnected = thermoConnected,
            thermoReading = null,
            thermoAtMillis = null,
            nibpRunning = false,
            nibp = LiveFeed.NibpState(status = -1, cuffMmhg = 0),
            nibpAtMillis = null,
            stethoPlugged = stethoPlugged,
            stethoAvailable = stethoPlugged || ykushPlugged,
            stethoStarting = stethoStarting,
            stethoRunning = stethoRunning,
            stethoPlaying = false,
            otoscopePlugged = otoscopePlugged,
            otoscopeAvailable = otoscopePlugged || ykushPlugged,
            otoscopeState = otoscopeState,
        ),
        wifi = wifi,
        ykushPlugged = ykushPlugged,
        usbMissing = usbMissing,
        appMissing = appMissing,
        localMode = localMode,
        sessionActive = sessionActive,
        teleInRoom = teleInRoom,
        callAudioChoice = callAudioChoice,
        jackPresent = jackPresent,
    )

    private fun row(s: StatusSnapshot, name: String, localScreen: Boolean = s.localMode): DeviceRow =
        StatusPanelUi.devices(s, localScreen).single { es(it.name) == name }

    private fun ok(text: String) = Pair(StatusTone.OK, text)
    private fun warn(text: String) = Pair(StatusTone.WARN, text)
    private fun bad(text: String) = Pair(StatusTone.BAD, text)
    private fun DeviceRow.pair() = Pair(tone, es(text))

    /** La cabecera como se lee en español: nombre y estado. */
    private fun WifiRow.read() = Pair(es(name), es(text))

    @Test
    fun rowsKeepTheirOrder() {
        assertEquals(
            listOf("SpO2", "ECG", "Presión", "Temperatura", "Otoscopio", "Estetoscopio", "Hub USB"),
            StatusPanelUi.devices(status()).map { es(it.name) },
        )
    }

    @Test
    fun boardRows() {
        for (name in listOf("SpO2", "ECG", "Presión")) {
            assertEquals(ok("Conectado"), row(status(), name).pair())
            // Conectada sin datos frescos: conectando.
            assertEquals(warn("Conectando"), row(status(lastDataAge = null), name).pair())
            assertEquals(warn("Conectando"), row(status(lastDataAge = 3.5), name).pair())
            assertEquals(bad("Desconectado"), row(status(berryUp = false), name).pair())
        }
    }

    @Test
    fun ecgWithLeadsOff() {
        assertEquals(warn("Electrodos sueltos"), row(status(leadOff = true), "ECG").pair())
        // Las otras filas de la plaqueta siguen conectadas.
        assertEquals(ok("Conectado"), row(status(leadOff = true), "SpO2").pair())
        // Sin plaqueta manda la plaqueta.
        assertEquals(bad("Desconectado"), row(status(berryUp = false, leadOff = true), "ECG").pair())
    }

    @Test
    fun thermometerRow() {
        assertEquals(ok("Conectado"), row(status(), "Temperatura").pair())
        assertEquals(bad("Desconectado"), row(status(thermoConnected = false), "Temperatura").pair())
    }

    @Test
    fun otoscopeRow() {
        assertEquals(ok("En vivo"), row(status(otoscopeState = OtoscopePower.State.ON), "Otoscopio").pair())
        for (state in listOf(OtoscopePower.State.POWERING_PORT, OtoscopePower.State.WAITING_CAMERA)) {
            assertEquals(warn("Conectando"), row(status(otoscopeState = state), "Otoscopio").pair())
        }
        assertEquals(ok("Conectado"), row(status(), "Otoscopio").pair())
        // En Local el puerto arranca apagado: no es un error.
        assertEquals(ok("Listo"), row(status(otoscopePlugged = false, localMode = true), "Otoscopio").pair())
        // También en la pantalla del PIN, antes de que empiece el modo local.
        assertEquals(ok("Listo"), row(status(otoscopePlugged = false), "Otoscopio", localScreen = true).pair())
        assertEquals(bad("Desconectado"), row(status(otoscopePlugged = false), "Otoscopio").pair())
        assertEquals(bad("Sin hub"), row(status(otoscopePlugged = false, ykushPlugged = false), "Otoscopio").pair())
        assertEquals(
            bad("Sin hub"),
            row(status(otoscopePlugged = false, ykushPlugged = false, localMode = true), "Otoscopio").pair(),
        )
    }

    @Test
    fun stethoscopeRow() {
        assertEquals(ok("En vivo"), row(status(stethoRunning = true), "Estetoscopio").pair())
        assertEquals(warn("Conectando"), row(status(stethoStarting = true), "Estetoscopio").pair())
        assertEquals(ok("Conectado"), row(status(), "Estetoscopio").pair())
        assertEquals(ok("Listo"), row(status(stethoPlugged = false, localMode = true), "Estetoscopio").pair())
        assertEquals(bad("Desconectado"), row(status(stethoPlugged = false), "Estetoscopio").pair())
        assertEquals(bad("Sin hub"), row(status(stethoPlugged = false, ykushPlugged = false), "Estetoscopio").pair())
    }

    @Test
    fun hubRow() {
        assertEquals(ok("Conectado"), row(status(), "Hub USB").pair())
        assertEquals(bad("Desconectado"), row(status(ykushPlugged = false), "Hub USB").pair())
        // Enchufado sin permiso: ámbar, no el rojo de desconectado, y se toca para pedirlo.
        val missing = row(status(usbMissing = setOf(UsbRole.HUB)), "Hub USB")
        assertEquals(warn("Falta permiso"), missing.pair())
        assertEquals(StatusAction.USB_PERMISSION, missing.action)
        // Al que le falta es a otro equipo: el hub sigue conectado.
        assertEquals(ok("Conectado"), row(status(usbMissing = setOf(UsbRole.THERMOMETER)), "Hub USB").pair())
        assertNull(row(status(), "Hub USB").action)
    }

    @Test
    fun boardWithoutUsbPermission() {
        val s = status(berryUp = false, lastDataAge = null, usbMissing = setOf(UsbRole.BOARD))
        for (name in listOf("SpO2", "ECG", "Presión")) {
            assertEquals(warn("Falta permiso"), row(s, name).pair())
            assertEquals(StatusAction.USB_PERMISSION, row(s, name).action)
        }
        // Desenchufada sigue en rojo, sin nada que tocar.
        assertNull(row(status(berryUp = false), "SpO2").action)
    }

    @Test
    fun thermometerWithoutUsbPermission() {
        val missing = row(status(thermoConnected = false, usbMissing = setOf(UsbRole.THERMOMETER)), "Temperatura")
        assertEquals(warn("Falta permiso"), missing.pair())
        assertEquals(StatusAction.USB_PERMISSION, missing.action)
    }

    @Test
    fun otoscopeWithoutPermissions() {
        val usb = row(status(usbMissing = setOf(UsbRole.OTOSCOPE)), "Otoscopio")
        assertEquals(warn("Falta permiso"), usb.pair())
        assertEquals(StatusAction.OTOSCOPE_PERMISSION, usb.action)
        val camera = row(status(appMissing = setOf(AppPermission.CAMERA)), "Otoscopio")
        assertEquals(warn("Falta permiso"), camera.pair())
        assertEquals(StatusAction.CAMERA, camera.action)
        // Primero el USB: sin él no hay cámara que pedir.
        assertEquals(
            StatusAction.OTOSCOPE_PERMISSION,
            row(status(usbMissing = setOf(UsbRole.OTOSCOPE), appMissing = setOf(AppPermission.CAMERA)), "Otoscopio").action,
        )
        // En vivo manda lo que se ve.
        assertEquals(ok("En vivo"), row(status(otoscopeState = OtoscopePower.State.ON, appMissing = setOf(AppPermission.CAMERA)), "Otoscopio").pair())
        assertNull(row(status(), "Otoscopio").action)
    }

    @Test
    fun stethoscopeWithoutMicrophone() {
        val missing = row(status(appMissing = setOf(AppPermission.MICROPHONE)), "Estetoscopio")
        assertEquals(warn("Falta permiso"), missing.pair())
        assertEquals(StatusAction.MICROPHONE, missing.action)
        assertNull(row(status(), "Estetoscopio").action)
        // La cámara no es del estetoscopio.
        assertEquals(ok("Conectado"), row(status(appMissing = setOf(AppPermission.CAMERA)), "Estetoscopio").pair())
    }

    @Test
    fun aMissingPermissionIsNotGreenInTheCount() {
        val state = StatusPanelUi.build(status(appMissing = setOf(AppPermission.MICROPHONE)), PanelMode.REMOTE)
        assertEquals(6, state.okCount)
        assertEquals(StatusTone.WARN, state.countTone)
    }

    @Test
    fun onHomeTheDevicesReadLikeTheLocalPanelAndNothingBlocksTheMode() {
        // En Inicio los puertos del otoscopio y del estetoscopio están apagados en reposo, como en el Panel Local.
        val home = StatusPanelUi.build(status(otoscopePlugged = false), PanelMode.HOME)
        val local = StatusPanelUi.build(status(otoscopePlugged = false), PanelMode.LOCAL_LOCKED)
        assertEquals(local.devices, home.devices)
        assertEquals(PanelMode.HOME, home.mode)
        assertEquals(null, home.modeBlocked)
    }

    @Test
    fun countCountsGreens() {
        val all = StatusPanelUi.build(status(), PanelMode.REMOTE)
        assertEquals(7, all.okCount)
        assertEquals(StatusTone.OK, all.countTone)

        val red = StatusPanelUi.build(status(otoscopePlugged = false), PanelMode.REMOTE)
        assertEquals(6, red.okCount)
        assertEquals(StatusTone.BAD, red.countTone)

        val amber = StatusPanelUi.build(status(stethoStarting = true), PanelMode.REMOTE)
        assertEquals(6, amber.okCount)
        assertEquals(StatusTone.WARN, amber.countTone)

        // Rojo y ámbar a la vez: manda el rojo.
        val both = StatusPanelUi.build(status(stethoStarting = true, thermoConnected = false), PanelMode.REMOTE)
        assertEquals(5, both.okCount)
        assertEquals(StatusTone.BAD, both.countTone)
    }

    @Test
    fun theWifiRowIsAlwaysTappable() {
        // Con red o sin red abre el panel de Wi-Fi de Android.
        assertEquals(StatusAction.OPEN_WIFI, StatusPanelUi.wifi(WifiState()).action)
        assertEquals(
            StatusAction.OPEN_WIFI,
            StatusPanelUi.wifi(WifiState(connected = true, validated = true, ssid = "Consultorio-5G", rssi = -55)).action,
        )
    }

    @Test
    fun wifiWithoutLocation() {
        val noName = WifiState(connected = true, validated = true, rssi = -55)
        val row = StatusPanelUi.wifi(noName, locationGranted = false)
        assertEquals(Pair("Wi-Fi conectado", "Falta permiso"), row.read())
        assertEquals(
            WifiRow(WifiIcon.WIFI, row.name, row.text, StatusTone.WARN, 4, offline = false, action = StatusAction.LOCATION),
            row,
        )
        // Sin internet importa más que el nombre.
        assertEquals("Sin internet", es(StatusPanelUi.wifi(noName.copy(validated = false), locationGranted = false).text))
        // Sin red, la ubicación no importa: se elige una red.
        assertEquals(StatusAction.OPEN_WIFI, StatusPanelUi.wifi(WifiState(), locationGranted = false).action)
        // Desde el snapshot.
        assertEquals(
            StatusAction.LOCATION,
            StatusPanelUi.build(status(wifi = noName, appMissing = setOf(AppPermission.LOCATION)), PanelMode.REMOTE).wifi.action,
        )
    }

    @Test
    fun wifiBars() {
        assertEquals(4, WifiState.bars(-50))
        assertEquals(4, WifiState.bars(-60))
        assertEquals(3, WifiState.bars(-61))
        assertEquals(3, WifiState.bars(-70))
        assertEquals(2, WifiState.bars(-71))
        assertEquals(2, WifiState.bars(-80))
        assertEquals(1, WifiState.bars(-81))
        assertEquals(0, WifiState(connected = true).level)
    }

    @Test
    fun wifiState() {
        val good = StatusPanelUi.wifi(WifiState(connected = true, validated = true, ssid = "Consultorio-5G", rssi = -65))
        assertEquals(Pair("Consultorio-5G", "Señal buena"), good.read())
        assertEquals(WifiRow(WifiIcon.WIFI, good.name, good.text, StatusTone.OK, 3, offline = false), good)

        val weak = StatusPanelUi.wifi(WifiState(connected = true, validated = true, ssid = "Consultorio-5G", rssi = -85))
        assertEquals(Pair("Consultorio-5G", "Señal débil"), weak.read())
        assertEquals(WifiRow(WifiIcon.WEAK, weak.name, weak.text, StatusTone.WARN, 1, offline = false), weak)

        val noInternet = StatusPanelUi.wifi(WifiState(connected = true, validated = false, ssid = "Consultorio-5G", rssi = -75))
        assertEquals(Pair("Consultorio-5G", "Sin internet"), noInternet.read())
        assertEquals(WifiRow(WifiIcon.WIFI, noInternet.name, noInternet.text, StatusTone.WARN, 2, offline = false), noInternet)

        val none = StatusPanelUi.wifi(WifiState())
        assertEquals(Pair("Sin Wi-Fi", "Tocá para elegir una red"), none.read())
        assertEquals(WifiRow(WifiIcon.OFF, none.name, none.text, StatusTone.BAD, 0, offline = true), none)

        // Sin el permiso de ubicación Android no da el nombre.
        assertEquals("Wi-Fi conectado", es(StatusPanelUi.wifi(WifiState(connected = true, validated = true, rssi = -55)).name))
    }

    @Test
    fun ssidWithoutQuotes() {
        assertEquals("Consultorio-5G", WifiState.cleanSsid("\"Consultorio-5G\""))
        assertNull(WifiState.cleanSsid("<unknown ssid>"))
        assertNull(WifiState.cleanSsid(""))
        assertNull(WifiState.cleanSsid(null))
    }

    @Test
    fun callRoute() {
        assertEquals(CallRoute.HEADSET, StatusPanelUi.callRoute(CALL_AUDIO_HEADSET, jackPresent = true))
        assertEquals(CallRoute.SPEAKER, StatusPanelUi.callRoute(CALL_AUDIO_SPEAKER, jackPresent = true))
        // Sin jack se ve Altavoz aunque el ajuste diga auricular.
        assertEquals(CallRoute.SPEAKER, StatusPanelUi.callRoute(CALL_AUDIO_HEADSET, jackPresent = false))
        assertEquals(CallRoute.SPEAKER, StatusPanelUi.callRoute(CALL_AUDIO_SPEAKER, jackPresent = false))
    }

    @Test
    fun callHelp() {
        assertEquals("Todo suena por el auricular.", es(StatusPanelUi.callHelp(CallRoute.HEADSET, true)))
        assertEquals(
            "La llamada va por el altavoz y el micrófono de la tablet. El estetoscopio sigue en el auricular.",
            es(StatusPanelUi.callHelp(CallRoute.SPEAKER, true)),
        )
        val noJack = "Sin auricular en el jack: la llamada va por el altavoz y el estetoscopio no suena."
        assertEquals(noJack, es(StatusPanelUi.callHelp(CallRoute.SPEAKER, false)))
        // Igual en los dos modos: el sonido es de toda la app, no sólo de la llamada.
        for (mode in listOf(PanelMode.REMOTE, PanelMode.LOCAL, PanelMode.LOCAL_LOCKED)) {
            val built = StatusPanelUi.build(status(jackPresent = false), mode)
            assertEquals(CallRoute.SPEAKER, built.callRoute)
            assertEquals(noJack, es(built.callHelp))
        }
    }

    @Test
    fun modeSwitchBlock() {
        assertNull(StatusSnapshot.modeSwitchBlock(sessionActive = false, teleInRoom = false))
        val text = "Hay una consulta en curso. Terminala para cambiar."
        assertEquals(text, StatusSnapshot.modeSwitchBlock(sessionActive = true, teleInRoom = false)?.let(::es))
        assertEquals(text, StatusSnapshot.modeSwitchBlock(sessionActive = false, teleInRoom = true)?.let(::es))
        assertEquals(text, StatusPanelUi.build(status(teleInRoom = true), PanelMode.REMOTE).modeBlocked?.let(::es))
        // Volver a ONDOCTOR365 desde el Panel Local no se bloquea.
        assertNull(StatusPanelUi.build(status(sessionActive = true), PanelMode.LOCAL_LOCKED).modeBlocked)
    }

    /** La misma regla leída en inglés y en portugués; el nombre del Wi-Fi no se traduce. */
    @Test
    fun theSameRowsInOtherLanguages() {
        val rows = StatusPanelUi.devices(status(berryUp = false))
        assertEquals(listOf("SpO2", "ECG", "BP", "Temperature", "Otoscope", "Stethoscope", "USB hub"), rows.map { it.name.inLanguage("en") })
        assertEquals(listOf("SpO2", "ECG", "Pressão", "Temperatura", "Otoscópio", "Estetoscópio", "Hub USB"), rows.map { it.name.inLanguage("pt") })
        assertEquals("Disconnected", rows[0].text.inLanguage("en"))
        assertEquals("Desconectado", rows[0].text.inLanguage("pt"))
        val wifi = StatusPanelUi.wifi(WifiState(connected = true, validated = true, ssid = "Consultorio-5G", rssi = -65))
        assertEquals("Consultorio-5G", wifi.name.inLanguage("en"))
        assertEquals("Consultorio-5G", wifi.name.inLanguage("pt"))
        assertEquals("Good signal", wifi.text.inLanguage("en"))
        assertEquals("Sinal bom", wifi.text.inLanguage("pt"))
        val leads = row(status(leadOff = true), "ECG").text
        assertEquals("Leads off", leads.inLanguage("en"))
        assertEquals("Eletrodos soltos", leads.inLanguage("pt"))
    }
}
