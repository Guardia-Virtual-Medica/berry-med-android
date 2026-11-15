// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import com.guardiavirtual.berrymed.config.CALL_AUDIO_HEADSET
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.net.WifiState
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.service.LocalSnapshot
import com.guardiavirtual.berrymed.service.StatusSnapshot
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
        usbPermissionMissing: Boolean = false,
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
        usbPermissionMissing = usbPermissionMissing,
        localMode = localMode,
        sessionActive = sessionActive,
        teleInRoom = teleInRoom,
        callAudioChoice = callAudioChoice,
        jackPresent = jackPresent,
    )

    private fun row(s: StatusSnapshot, name: String, localScreen: Boolean = s.localMode): DeviceRow =
        StatusPanelUi.devices(s, localScreen).single { it.name == name }

    private fun ok(text: String) = Pair(StatusTone.OK, text)
    private fun warn(text: String) = Pair(StatusTone.WARN, text)
    private fun bad(text: String) = Pair(StatusTone.BAD, text)
    private fun DeviceRow.pair() = Pair(tone, text)

    @Test
    fun rowsKeepTheirOrder() {
        assertEquals(
            listOf("SpO2", "ECG", "Presión", "Temperatura", "Otoscopio", "Estetoscopio", "Hub USB"),
            StatusPanelUi.devices(status()).map { it.name },
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
        assertEquals(bad("Sin permiso USB"), row(status(usbPermissionMissing = true), "Hub USB").pair())
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
        assertEquals(WifiRow(WifiIcon.WIFI, "Consultorio-5G", "Señal buena", StatusTone.OK, 3, offline = false), good)

        val weak = StatusPanelUi.wifi(WifiState(connected = true, validated = true, ssid = "Consultorio-5G", rssi = -85))
        assertEquals(WifiRow(WifiIcon.WEAK, "Consultorio-5G", "Señal débil", StatusTone.WARN, 1, offline = false), weak)

        val noInternet = StatusPanelUi.wifi(WifiState(connected = true, validated = false, ssid = "Consultorio-5G", rssi = -75))
        assertEquals(WifiRow(WifiIcon.WIFI, "Consultorio-5G", "Sin internet", StatusTone.WARN, 2, offline = false), noInternet)

        val none = StatusPanelUi.wifi(WifiState())
        assertEquals(WifiRow(WifiIcon.OFF, "Sin Wi-Fi", "Tocá para elegir una red", StatusTone.BAD, 0, offline = true), none)

        // Sin el permiso de ubicación Android no da el nombre.
        assertEquals("Wi-Fi", StatusPanelUi.wifi(WifiState(connected = true, validated = true, rssi = -55)).name)
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
        assertEquals(CallRoute.TABLET, StatusPanelUi.callRoute(CALL_AUDIO_SPEAKER, jackPresent = true))
        // Sin jack se ve Tablet aunque el ajuste diga auricular.
        assertEquals(CallRoute.TABLET, StatusPanelUi.callRoute(CALL_AUDIO_HEADSET, jackPresent = false))
        assertEquals(CallRoute.TABLET, StatusPanelUi.callRoute(CALL_AUDIO_SPEAKER, jackPresent = false))
    }

    @Test
    fun callHelp() {
        assertEquals("Se escucha y se habla por el auricular.", StatusPanelUi.callHelp(CallRoute.HEADSET, true, local = false))
        assertEquals("Parlante y micrófono de la tablet.", StatusPanelUi.callHelp(CallRoute.TABLET, true, local = false))
        assertEquals("No hay auricular en el jack: se usa la tablet.", StatusPanelUi.callHelp(CallRoute.TABLET, false, local = false))
        assertEquals(
            "Para la teleconsulta. Se escucha y se habla por el auricular.",
            StatusPanelUi.callHelp(CallRoute.HEADSET, true, local = true),
        )
        val built = StatusPanelUi.build(status(jackPresent = false), PanelMode.LOCAL)
        assertEquals(CallRoute.TABLET, built.callRoute)
        assertEquals("Para la teleconsulta. No hay auricular en el jack: se usa la tablet.", built.callHelp)
    }

    @Test
    fun modeSwitchBlock() {
        assertNull(StatusSnapshot.modeSwitchBlock(sessionActive = false, teleInRoom = false))
        val text = "Hay una consulta en curso. Terminala para cambiar."
        assertEquals(text, StatusSnapshot.modeSwitchBlock(sessionActive = true, teleInRoom = false))
        assertEquals(text, StatusSnapshot.modeSwitchBlock(sessionActive = false, teleInRoom = true))
        assertEquals(text, StatusPanelUi.build(status(teleInRoom = true), PanelMode.REMOTE).modeBlocked)
        // Volver a ONDOCTOR365 desde el Panel Local no se bloquea.
        assertNull(StatusPanelUi.build(status(sessionActive = true), PanelMode.LOCAL_LOCKED).modeBlocked)
    }
}
