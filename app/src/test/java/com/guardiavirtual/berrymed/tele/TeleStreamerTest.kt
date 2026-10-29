// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeleStreamerTest {
    /** Anota cada llamada; las respuestas se cambian por prueba. */
    private class FakeBackend : TeleBackend {
        val calls = ArrayList<String>()
        var blockedReason: String? = null
        var token: TokenOutcome = TokenOutcome.Ok(TeleCredentials("wss://lk", "encounter-e1", "jwt"))
        var connectError: String? = null
        var publishError: String? = null

        override fun blocked(instrument: Instrument): String? = blockedReason

        override fun fetchToken(encounterId: String): TokenOutcome {
            calls += "token $encounterId"
            return token
        }

        override fun connect(credentials: TeleCredentials): String? {
            calls += "connect ${credentials.serverUrl}"
            return connectError
        }

        override fun publish(instrument: Instrument): String? {
            calls += "publish ${instrument.label}"
            return publishError
        }

        override fun unpublish(instrument: Instrument) {
            calls += "unpublish ${instrument.label}"
        }

        override fun disconnect() {
            calls += "disconnect"
        }
    }

    private val backend = FakeBackend()
    private val streamer = TeleStreamer(backend, log = {}, executor = { it.run() })

    private fun start(instrument: Instrument, encounter: String = "e1") = streamer.order(TeleOrder(instrument, true, encounter))

    private fun stop(instrument: Instrument, encounter: String = "e1") = streamer.order(TeleOrder(instrument, false, encounter))

    @Test
    fun theFirstOrderAsksForTheTokenEntersAndPublishes() {
        start(Instrument.OTOSCOPE)
        assertEquals(listOf("token e1", "connect wss://lk", "publish otoscopio"), backend.calls)
        assertEquals(RoomState.CONNECTED, streamer.status.room)
        assertEquals(Instrument.OTOSCOPE, streamer.status.instrument)
        assertEquals("e1", streamer.status.encounterId)
    }

    @Test
    fun startingOneInstrumentUnpublishesTheOtherInTheSameRoom() {
        start(Instrument.OTOSCOPE)
        backend.calls.clear()
        start(Instrument.STETHOSCOPE)
        assertEquals(listOf("unpublish otoscopio", "publish estetoscopio"), backend.calls)
        assertEquals(Instrument.STETHOSCOPE, streamer.status.instrument)
    }

    @Test
    fun startingTheSameInstrumentAgainDoesNothing() {
        start(Instrument.STETHOSCOPE)
        backend.calls.clear()
        start(Instrument.STETHOSCOPE)
        assertEquals(emptyList(), backend.calls)
    }

    @Test
    fun stoppingTheLastInstrumentLeavesTheRoom() {
        start(Instrument.STETHOSCOPE)
        backend.calls.clear()
        stop(Instrument.STETHOSCOPE)
        assertEquals(listOf("unpublish estetoscopio", "disconnect"), backend.calls)
        assertEquals(TeleStatus(), streamer.status)
    }

    @Test
    fun stoppingTheInstrumentThatIsNotPublishedLeavesItAlone() {
        start(Instrument.OTOSCOPE)
        backend.calls.clear()
        stop(Instrument.STETHOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals(Instrument.OTOSCOPE, streamer.status.instrument)
    }

    @Test
    fun theEndOfTheConsultationClosesEverything() {
        start(Instrument.OTOSCOPE)
        backend.calls.clear()
        streamer.stopAll("fin de la consulta")
        assertEquals(listOf("unpublish otoscopio", "disconnect"), backend.calls)
        assertEquals(RoomState.DISCONNECTED, streamer.status.room)
        // Sin nada abierto, no hace nada.
        backend.calls.clear()
        streamer.stopAll("fin de la consulta")
        assertEquals(emptyList(), backend.calls)
    }

    @Test
    fun anOrderWithTheLocalPanelOpenIsIgnored() {
        backend.blockedReason = "el Panel Local está abierto"
        start(Instrument.OTOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals(RoomState.DISCONNECTED, streamer.status.room)
        assertEquals("el Panel Local está abierto", streamer.status.message)
    }

    @Test
    fun aTokenErrorLeavesAnErrorAndIsNotRetriedOnItsOwn() {
        backend.token = TokenOutcome.Failed("La API respondió 409")
        start(Instrument.STETHOSCOPE)
        assertEquals(listOf("token e1"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
        assertEquals("La API respondió 409", streamer.status.message)
        // Sólo la próxima orden vuelve a pedirlo.
        start(Instrument.STETHOSCOPE)
        assertEquals(listOf("token e1", "token e1"), backend.calls)
    }

    @Test
    fun aRoomThatDoesNotOpenLeavesAnError() {
        backend.connectError = "No se pudo entrar a la sala"
        start(Instrument.OTOSCOPE)
        assertEquals(listOf("token e1", "connect wss://lk"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
        assertNull(streamer.status.instrument)
    }

    @Test
    fun anInstrumentThatDoesNotOpenLeavesTheRoomAndAnError() {
        backend.publishError = "El otoscopio no aparece"
        start(Instrument.OTOSCOPE)
        assertEquals(listOf("token e1", "connect wss://lk", "publish otoscopio", "disconnect"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
        assertEquals("El otoscopio no aparece", streamer.status.message)
    }

    @Test
    fun anOrderFromAnotherEncounterClosesTheRoomAndOpensTheNewOne() {
        start(Instrument.OTOSCOPE, "e1")
        backend.calls.clear()
        start(Instrument.OTOSCOPE, "e2")
        assertEquals(listOf("unpublish otoscopio", "disconnect", "token e2", "connect wss://lk", "publish otoscopio"), backend.calls)
        assertEquals("e2", streamer.status.encounterId)
    }

    @Test
    fun aStopFromAnotherEncounterIsIgnored() {
        start(Instrument.OTOSCOPE, "e1")
        backend.calls.clear()
        stop(Instrument.OTOSCOPE, "e2")
        assertEquals(emptyList(), backend.calls)
        assertEquals(Instrument.OTOSCOPE, streamer.status.instrument)
    }

    @Test
    fun theManualTestDoesNotAskTheApiAndOrdersDoNotTouchIt() {
        streamer.startManual(" ws://10.0.2.2:7880 ", " jwt ", Instrument.STETHOSCOPE)
        assertEquals(listOf("connect ws://10.0.2.2:7880", "publish estetoscopio"), backend.calls)
        assertTrue(streamer.status.manual)
        backend.calls.clear()
        start(Instrument.OTOSCOPE)
        stop(Instrument.STETHOSCOPE)
        streamer.stopAll("fin de la consulta")
        assertEquals(emptyList(), backend.calls)
        // El Panel Local sí la corta.
        streamer.stopAll("se abrió el Panel Local", includeManual = true)
        assertEquals(listOf("unpublish estetoscopio", "disconnect"), backend.calls)
        assertEquals(TeleStatus(), streamer.status)
    }

    @Test
    fun theManualTestNeedsServerAndToken() {
        streamer.startManual("", "jwt", Instrument.OTOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
    }

    @Test
    fun aLostRoomReleasesTheInstrumentAndLeavesAnError() {
        start(Instrument.STETHOSCOPE)
        backend.calls.clear()
        streamer.roomLost("signal_close")
        assertEquals(listOf("unpublish estetoscopio", "disconnect"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
        assertNull(streamer.status.instrument)
    }

    @Test
    fun anExceptionDoesNotEscapeAndReleasesEverything() {
        val failing = object : TeleBackend by backend {
            override fun publish(instrument: Instrument): String? = throw IllegalStateException("roto")
        }
        val streamer = TeleStreamer(failing, log = {}, executor = { it.run() })
        streamer.order(TeleOrder(Instrument.OTOSCOPE, true, "e1"))
        assertEquals(RoomState.ERROR, streamer.status.room)
        assertEquals("disconnect", backend.calls.last())
    }
}
