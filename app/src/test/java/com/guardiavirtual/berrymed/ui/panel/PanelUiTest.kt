// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.audio.StethoFilter
import com.guardiavirtual.berrymed.audio.StethoRecording
import com.guardiavirtual.berrymed.otoscope.OtoscopePower
import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.NO_PAIR
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.service.LocalSnapshot
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelUiTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 29, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Una plaqueta transmitiendo con todo en orden y nada más enchufado. */
    private fun snapshot(
        berryUp: Boolean = true,
        lastDataAge: Double? = 0.2,
        vitals: VitalSigns = VitalSigns(heartRate = "71", spo2Pulse = "98/73", respRate = "17"),
        ecgInfo: EcgInfo = EcgInfo(leadOff = false, weakSignal = false),
        thermoConnected: Boolean = false,
        thermoReading: String? = null,
        thermoAtMillis: Long? = null,
        nibpRunning: Boolean = false,
        nibp: LiveFeed.NibpState = LiveFeed.NibpState(status = -1, cuffMmhg = 0),
        nibpAtMillis: Long? = null,
        stethoPlugged: Boolean = false,
        stethoAvailable: Boolean = false,
        stethoStarting: Boolean = false,
        stethoRunning: Boolean = false,
        stethoFilter: StethoFilter = StethoFilter.DEFAULT,
        stethoRecordingSeconds: Double? = null,
        stethoRecordings: List<StethoRecording> = emptyList(),
        stethoReplaying: Int? = null,
        otoscopePlugged: Boolean = false,
        otoscopeAvailable: Boolean = false,
        otoscopeState: OtoscopePower.State = OtoscopePower.State.OFF,
    ) = LocalSnapshot(
        berryUp = berryUp,
        lastDataAge = lastDataAge,
        vitals = vitals,
        ecgInfo = ecgInfo,
        thermoConnected = thermoConnected,
        thermoReading = thermoReading,
        thermoAtMillis = thermoAtMillis,
        nibpRunning = nibpRunning,
        nibp = nibp,
        nibpAtMillis = nibpAtMillis,
        stethoPlugged = stethoPlugged,
        stethoAvailable = stethoAvailable,
        stethoStarting = stethoStarting,
        stethoRunning = stethoRunning,
        stethoPlaying = false,
        stethoFilter = stethoFilter,
        stethoRecordingSeconds = stethoRecordingSeconds,
        stethoRecordings = stethoRecordings,
        stethoReplaying = stethoReplaying,
        otoscopePlugged = otoscopePlugged,
        otoscopeAvailable = otoscopeAvailable,
        otoscopeState = otoscopeState,
    )

    private fun build(s: LocalSnapshot, now: Long = at(10, 12), screen: ScreenState = ScreenState()) =
        PanelUi().build(s, now, screen, zone)

    @Test
    fun berryChipsFollowTheBoard() {
        val live = PanelUi.chips(snapshot())
        assertEquals(Link.CONNECTED, live.ecg)
        assertEquals(Link.CONNECTED, live.spo2)
        assertEquals(Link.CONNECTED, live.pressure)
        // Conectada pero callada: todavía conectando.
        assertEquals(Link.CONNECTING, PanelUi.chips(snapshot(lastDataAge = null)).ecg)
        assertEquals(Link.CONNECTING, PanelUi.chips(snapshot(lastDataAge = 3.5)).spo2)
        assertEquals(Link.CONNECTED, PanelUi.chips(snapshot(lastDataAge = 3.0)).pressure)
        assertEquals(Link.DISCONNECTED, PanelUi.chips(snapshot(berryUp = false, lastDataAge = 0.1)).ecg)
    }

    @Test
    fun thermometerChip() {
        assertEquals(Link.DISCONNECTED, PanelUi.chips(snapshot()).temperature)
        assertEquals(Link.CONNECTED, PanelUi.chips(snapshot(thermoConnected = true)).temperature)
    }

    @Test
    fun otoscopeChip() {
        assertEquals(Link.DISCONNECTED, PanelUi.chips(snapshot(otoscopeAvailable = true)).otoscope)
        assertEquals(Link.CONNECTED, PanelUi.chips(snapshot(otoscopePlugged = true)).otoscope)
        for (state in listOf(OtoscopePower.State.POWERING_PORT, OtoscopePower.State.WAITING_CAMERA)) {
            assertEquals(Link.CONNECTING, PanelUi.chips(snapshot(otoscopePlugged = true, otoscopeState = state)).otoscope)
        }
        assertEquals(Link.CONNECTED, PanelUi.chips(snapshot(otoscopeState = OtoscopePower.State.ON)).otoscope)
    }

    @Test
    fun stethoscopeChip() {
        assertEquals(Link.DISCONNECTED, PanelUi.chips(snapshot(stethoAvailable = true)).stethoscope)
        assertEquals(Link.CONNECTED, PanelUi.chips(snapshot(stethoPlugged = true)).stethoscope)
        assertEquals(Link.CONNECTED, PanelUi.chips(snapshot(stethoRunning = true)).stethoscope)
        assertEquals(Link.CONNECTING, PanelUi.chips(snapshot(stethoPlugged = true, stethoStarting = true)).stethoscope)
    }

    @Test
    fun ecgSignal() {
        assertEquals(Status("Señal buena", Tone.OK), PanelUi.signal(snapshot()))
        assertEquals(Status("Señal débil", Tone.WARN), PanelUi.signal(snapshot(ecgInfo = EcgInfo(leadOff = false, weakSignal = true))))
        assertEquals(Tone.FAINT, PanelUi.signal(snapshot(ecgInfo = EcgInfo(leadOff = true))).tone)
        assertEquals("Sin señal", PanelUi.signal(snapshot(ecgInfo = EcgInfo())).text)
        assertEquals("Sin señal", PanelUi.signal(snapshot(lastDataAge = 7.0)).text)
        assertEquals("Sin señal", PanelUi.signal(snapshot(berryUp = false)).text)
    }

    @Test
    fun heartNoteTellsLooseElectrodesOrArrhythmia() {
        assertEquals("", PanelUi.heartNote(snapshot()))
        assertEquals("Electrodos sueltos", PanelUi.heartNote(snapshot(ecgInfo = EcgInfo(leadOff = true))))
        assertEquals("taquicardia", PanelUi.heartNote(snapshot(ecgInfo = EcgInfo(leadOff = false, hrv = "taquicardia"))))
    }

    @Test
    fun pressureResultCarriesMeanAndTime() {
        val s = snapshot(
            vitals = VitalSigns(nibp = "120/80"),
            nibp = LiveFeed.NibpState(status = 0, cuffMmhg = 0),
            nibpAtMillis = at(10, 7),
        )
        assertEquals(PressureCard.Result("120/80", "(93)", "10:07 · hace 5 min"), build(s).pressure)
        assertEquals(PressureCard.Result("120/80", "(93)", "10:07 · recién"), build(s, now = at(10, 7)).pressure)
        // Una presión de antes de abrir el panel, sin hora conocida.
        assertEquals(PressureCard.Result("120/80", "(93)", ""), build(s.copy(nibpAtMillis = null)).pressure)
    }

    @Test
    fun pressureNeverMeasuredOrWithoutResult() {
        assertEquals(PressureCard.Empty, build(snapshot(vitals = VitalSigns(nibp = NO_PAIR))).pressure)
        // BIT5~2 = 0x4: manguito flojo, un final de la plaqueta sin presión.
        val looseCuff = LiveFeed.NibpState(status = 0x4 shl 2, cuffMmhg = 0)
        assertTrue(Pm6750Protocol.nibpIsTerminal(looseCuff.status))
        assertEquals(PressureCard.NoResult, build(snapshot(nibp = looseCuff)).pressure)
        // Cancelada mientras inflaba: la plaqueta no llegó a mandar un final.
        val inflating = LiveFeed.NibpState(status = 0x1 shl 2, cuffMmhg = 120)
        assertEquals(PressureCard.NoResult, build(snapshot(nibp = inflating)).pressure)
    }

    @Test
    fun pressureMeasuringShowsCuffAndProgress() {
        val card = build(snapshot(nibpRunning = true, nibp = LiveFeed.NibpState(status = 4, cuffMmhg = 129))).pressure
        card as PressureCard.Measuring
        assertEquals("129", card.cuff)
        assertEquals(PanelUi.MEASURING, card.phase)
        assertEquals(129f / 175f, card.progress, 0.001f)
        val over = build(snapshot(nibpRunning = true, nibp = LiveFeed.NibpState(status = 4, cuffMmhg = 200))).pressure
        assertEquals(1f, (over as PressureCard.Measuring).progress)
        val none = build(snapshot(nibpRunning = true, nibp = LiveFeed.NibpState(status = -1, cuffMmhg = 0))).pressure
        assertEquals("—", (none as PressureCard.Measuring).cuff)
    }

    @Test
    fun phaseInflatesWhileRisingAndMeasuresWhileFalling() {
        val ui = PanelUi()
        assertEquals(PanelUi.MEASURING, ui.phase(true, 0))
        assertEquals(PanelUi.MEASURING, ui.phase(true, 20))
        assertEquals(PanelUi.INFLATING, ui.phase(true, 60))
        assertEquals(PanelUi.INFLATING, ui.phase(true, 60))
        assertEquals(PanelUi.INFLATING, ui.phase(true, 170))
        assertEquals(PanelUi.MEASURING, ui.phase(true, 160))
        assertEquals(PanelUi.MEASURING, ui.phase(true, 160))
        assertEquals("", ui.phase(false, 0))
        // La medición siguiente arranca sin el valor de la anterior.
        assertEquals(PanelUi.MEASURING, ui.phase(true, 150))
    }

    @Test
    fun temperatureCard() {
        assertEquals(TempCard.Disconnected, PanelUi.temperature(snapshot(), zone))
        // Desconectado manda aunque haya quedado una lectura.
        assertEquals(TempCard.Disconnected, PanelUi.temperature(snapshot(thermoReading = "36.6"), zone))
        assertEquals(TempCard.Waiting, PanelUi.temperature(snapshot(thermoConnected = true), zone))
        assertEquals(TempCard.Waiting, PanelUi.temperature(snapshot(thermoConnected = true, thermoReading = "-"), zone))
        assertEquals(
            TempCard.Reading("36,6", "IR · 10:12"),
            PanelUi.temperature(snapshot(thermoConnected = true, thermoReading = "36.6", thermoAtMillis = at(10, 12)), zone),
        )
    }

    @Test
    fun dockTilesWithoutHardwareSayDisconnectedAndDoNotOpen() {
        val state = build(snapshot(), screen = ScreenState(camera = CameraState.LIVE))
        assertEquals(Tile(false, Status("desconectado", Tone.FAINT)), state.otoscopeTile)
        assertEquals(Tile(false, Status("desconectado", Tone.FAINT)), state.stethoscopeTile)
        assertEquals(Tile(true, Status("Lista", Tone.MUTED)), state.cameraTile)
        val noCamera = build(snapshot(), screen = ScreenState(camera = CameraState.MISSING))
        assertEquals(Tile(false, Status("desconectado", Tone.FAINT)), noCamera.cameraTile)
    }

    @Test
    fun dockTilesWithHardware() {
        val off = build(snapshot(otoscopeAvailable = true, stethoAvailable = true))
        assertTrue(off.otoscopeTile.enabled)
        assertEquals("Apagado", off.otoscopeTile.status.text)
        assertEquals(Status("Conectado", Tone.MUTED), off.stethoscopeTile.status)
        val on = build(
            snapshot(otoscopeAvailable = true, otoscopeState = OtoscopePower.State.ON, stethoAvailable = true, stethoRunning = true),
            screen = ScreenState(otoscopeLabel = "por UVC", camera = CameraState.PAUSED),
        )
        assertEquals(Status("En vivo", Tone.OK), on.otoscopeTile.status)
        assertEquals(Status("En vivo · UVC", Tone.OK), on.otoscopeStatus)
        assertEquals(Status("Escuchando", Tone.OK), on.stethoscopeTile.status)
        assertEquals(Status("En pausa", Tone.WARN), on.cameraTile.status)
        val waiting = build(snapshot(otoscopeAvailable = true, otoscopeState = OtoscopePower.State.WAITING_CAMERA))
        assertEquals(Status("Conectando", Tone.WARN), waiting.otoscopeTile.status)
    }

    @Test
    fun anOpenDeviceCanAlwaysBeClosedFromItsTile() {
        val state = build(snapshot(), screen = ScreenState(openDevice = PanelDevice.OTOSCOPE))
        assertTrue(state.otoscopeTile.enabled)
        assertFalse(state.stethoscopeTile.enabled)
    }

    @Test
    fun cameraHeaderSaysWhichLens() {
        assertEquals("En vivo · trasera", PanelUi.cameraStatus(ScreenState(camera = CameraState.LIVE)).text)
        assertEquals("En vivo · frontal", PanelUi.cameraStatus(ScreenState(camera = CameraState.LIVE, cameraFront = true)).text)
    }

    @Test
    fun measureNeedsTheBoardAndNoMeasurementRunning() {
        assertTrue(build(snapshot()).canMeasure)
        assertFalse(build(snapshot(berryUp = false)).canMeasure)
        assertFalse(build(snapshot(nibpRunning = true)).canMeasure)
    }

    @Test
    fun vitalsUseLongDashesAndComma() {
        val state = build(snapshot(vitals = VitalSigns(heartRate = "- -", spo2Pulse = "97/-", respRate = "16")))
        assertEquals("—", state.heartRate)
        assertEquals("97", state.saturation)
        assertEquals("—", state.pulse)
        assertEquals("16", state.respiration)
    }

    @Test
    fun gainAndSpeedSegments() {
        assertEquals(0.5f, PanelUi.gainFactor(5f))
        assertEquals(1f, PanelUi.gainFactor(EcgPaperView.MM_PER_MV))
        assertEquals(2f, PanelUi.gainFactor(20f))
        assertEquals(listOf("12,5", "25", "50"), PanelUi.SPEEDS.map(PanelUi::speedLabel))
        // 100 mm de pantalla: 8 s a 12,5 mm/s, 4 s a 25 y 2 s a 50, a 250 muestras por segundo.
        val samples = PanelUi.SPEEDS.map { SweepTrace.samplesPerScreen(1000f, 10f, it, 250f) }
        assertEquals(listOf(2000, 1000, 500), samples)
    }

    @Test
    fun leadModes() {
        assertEquals(listOf("II"), EcgLeads.II.leads)
        assertEquals(listOf("I", "II", "III"), EcgLeads.THREE.leads)
        assertEquals(7, EcgLeads.SEVEN.leads.size)
    }

    @Test
    fun stethoscopeVolumeStepsByOneBetweenZeroAndTen() {
        assertEquals(7, PanelUi.stethoVolumeStep(6, up = true))
        assertEquals(5, PanelUi.stethoVolumeStep(6, up = false))
        assertEquals(10, PanelUi.stethoVolumeStep(10, up = true))
        assertEquals(0, PanelUi.stethoVolumeStep(0, up = false))
        assertEquals(1, PanelUi.stethoVolumeStep(0, up = true))
    }

    @Test
    fun stethoscopePrimaryListensThenRecords() {
        val idle = PanelUi.stethoButtons(snapshot(stethoAvailable = true))
        assertEquals(StethoButtons(StethoPrimary.LISTEN, primaryEnabled = true, stopEnabled = false, remaining = 15, filters = true), idle)
        assertFalse(PanelUi.stethoButtons(snapshot()).primaryEnabled)
        assertFalse(PanelUi.stethoButtons(snapshot(stethoAvailable = true, stethoStarting = true)).primaryEnabled)
        val listening = PanelUi.stethoButtons(snapshot(stethoAvailable = true, stethoRunning = true))
        assertEquals(StethoButtons(StethoPrimary.RECORD, primaryEnabled = true, stopEnabled = true, remaining = 15, filters = true), listening)
    }

    @Test
    fun recordingCountsDownAndLocksTheFilter() {
        val s = snapshot(stethoAvailable = true, stethoRunning = true, stethoRecordingSeconds = 2.0)
        assertEquals(
            StethoButtons(StethoPrimary.RECORDING, primaryEnabled = true, stopEnabled = true, remaining = 13, filters = false),
            PanelUi.stethoButtons(s),
        )
        assertEquals(15, PanelUi.recordingRemaining(0.0))
        assertEquals(15, PanelUi.recordingRemaining(0.5))
        assertEquals(1, PanelUi.recordingRemaining(14.2))
        assertEquals(0, PanelUi.recordingRemaining(15.0))
        val state = build(s)
        assertEquals(Status("Grabando", Tone.RECORDING), state.stethoscopeStatus)
        assertEquals(Status("Grabando · 13 s", Tone.RECORDING), state.stethoscopeTile.status)
    }

    @Test
    fun recordingsShowNewestFirstWithTheirTimeAndWhichOneIsPlaying() {
        val list = listOf(
            StethoRecording(1, StethoFilter.HEART, at(10, 5), ShortArray(0)),
            StethoRecording(2, StethoFilter.LUNG, at(10, 9), ShortArray(0)),
        )
        val rows = build(snapshot(stethoAvailable = true, stethoRecordings = list, stethoReplaying = 1)).recordings
        assertEquals(
            listOf(
                RecordingRow(2, StethoFilter.LUNG, "10:09", playing = false),
                RecordingRow(1, StethoFilter.HEART, "10:05", playing = true),
            ),
            rows,
        )
        assertTrue(build(snapshot()).recordings.isEmpty())
        val replaying = build(snapshot(stethoAvailable = true, stethoRecordings = list, stethoReplaying = 2))
        assertEquals(Status("Reproduciendo", Tone.OK), replaying.stethoscopeStatus)
    }

    @Test
    fun theFilterComesFromTheRuntime() {
        assertEquals(StethoFilter.HEART, build(snapshot()).stethoFilter)
        assertEquals(StethoFilter.WIDE, build(snapshot(stethoFilter = StethoFilter.WIDE)).stethoFilter)
    }

    @Test
    fun liveVideoFreezesWithHelpAndItsOwnButton() {
        assertEquals(VideoButtons(VideoPrimary.FREEZE, discard = false, help = true), PanelUi.videoButtons(VideoMode.LIVE))
    }

    @Test
    fun frozenVideoSavesOrDiscards() {
        assertEquals(VideoButtons(VideoPrimary.SAVE, discard = true, help = false), PanelUi.videoButtons(VideoMode.FROZEN))
    }

    @Test
    fun aSavedCaptureIsNotSavedAgain() {
        assertEquals(VideoButtons(null, discard = true, help = false, backToLive = true), PanelUi.videoButtons(VideoMode.REVIEW))
        // Congelado sin guardar sí se descarta: el botón sigue diciendo "Descartar y volver".
        assertFalse(PanelUi.videoButtons(VideoMode.FROZEN).backToLive)
    }
}
