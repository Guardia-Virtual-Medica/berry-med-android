// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeleStreamerTest {
    /** Anota cada llamada; las respuestas se cambian por prueba. */
    private class FakeBackend : TeleBackend {
        val calls = ArrayList<String>()
        var blockedReason: String? = null
        var connectError: String? = null
        var publishError: String? = null

        override fun blocked(instrument: Instrument): String? = blockedReason

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

    private var now = 1_000_000L
    private val backend = FakeBackend()
    private val logs = ArrayList<String>()
    private val streamer = TeleStreamer(backend, log = { logs += it }, executor = { it.run() }, clock = { now })

    /** Un JWT de mentira (sin firma válida): sólo importan la sala y el vencimiento. */
    private fun jwt(room: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val body = """{"exp":2000,"video":{"room":"$room","roomJoin":true}}"""
        return "${encoder.encodeToString("{}".toByteArray())}.${encoder.encodeToString(body.toByteArray())}.firma"
    }

    private val first = TeleCredentials("wss://gvm.livekit.cloud", jwt("encounter-e1"))
    private val second = TeleCredentials("wss://gvm.livekit.cloud", jwt("encounter-e2"))

    private fun start(instrument: Instrument, encounter: String = "e1") = streamer.order(TeleOrder(instrument, true, encounter))

    private fun stop(instrument: Instrument, encounter: String = "e1") = streamer.order(TeleOrder(instrument, false, encounter))

    @Test
    fun theTokenOfTheKioskEntersTheRoomWithoutPublishing() {
        streamer.onKioskToken(first)
        assertEquals(listOf("connect wss://gvm.livekit.cloud"), backend.calls)
        assertEquals(RoomState.CONNECTED, streamer.status.room)
        assertEquals("encounter-e1", streamer.status.roomName)
        assertNull(streamer.status.instrument)
        assertEquals(now, streamer.status.tokenSeenAtMillis)
        assertEquals(2_000_000L, streamer.status.tokenExpiresAtMillis)
    }

    @Test
    fun theSameTokenSeenAgainDoesNothing() {
        streamer.onKioskToken(first)
        backend.calls.clear()
        streamer.onKioskToken(first)
        assertEquals(emptyList(), backend.calls)
    }

    @Test
    fun theTokenNeverGoesToTheLog() {
        streamer.onKioskToken(first)
        start(Instrument.OTOSCOPE)
        assertTrue(logs.none { it.contains(first.token) }, logs.joinToString("\n"))
    }

    @Test
    fun ordersPublishAndUnpublishInsideTheRoom() {
        streamer.onKioskToken(first)
        backend.calls.clear()
        start(Instrument.OTOSCOPE)
        start(Instrument.STETHOSCOPE)
        stop(Instrument.STETHOSCOPE)
        assertEquals(listOf("publish otoscopio", "unpublish otoscopio", "publish estetoscopio", "unpublish estetoscopio"), backend.calls)
        // Apagar el último no sale de la sala.
        assertEquals(RoomState.CONNECTED, streamer.status.room)
        assertNull(streamer.status.instrument)
    }

    @Test
    fun stoppingTheInstrumentThatIsNotPublishedLeavesItAlone() {
        streamer.onKioskToken(first)
        start(Instrument.OTOSCOPE)
        backend.calls.clear()
        stop(Instrument.STETHOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals(Instrument.OTOSCOPE, streamer.status.instrument)
    }

    @Test
    fun anOrderWithoutARoomIsIgnored() {
        start(Instrument.OTOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals(RoomState.DISCONNECTED, streamer.status.room)
    }

    @Test
    fun anOrderOfAnotherEncounterIsIgnored() {
        streamer.onKioskToken(first)
        backend.calls.clear()
        start(Instrument.OTOSCOPE, "e2")
        assertEquals(emptyList(), backend.calls)
    }

    @Test
    fun theEndOfTheVitalSignsOnlyUnpublishes() {
        streamer.onKioskToken(first)
        start(Instrument.STETHOSCOPE)
        backend.calls.clear()
        streamer.unpublishAll("fin de la toma de signos")
        assertEquals(listOf("unpublish estetoscopio"), backend.calls)
        assertEquals(RoomState.CONNECTED, streamer.status.room)
    }

    @Test
    fun aNewTokenLeavesTheOldRoomAndEntersTheNewOne() {
        streamer.onKioskToken(first)
        start(Instrument.OTOSCOPE)
        backend.calls.clear()
        streamer.onKioskToken(second)
        assertEquals(listOf("unpublish otoscopio", "disconnect", "connect wss://gvm.livekit.cloud"), backend.calls)
        assertEquals("encounter-e2", streamer.status.roomName)
    }

    @Test
    fun theKioskBackHomeLeavesTheRoom() {
        streamer.onKioskToken(first)
        start(Instrument.STETHOSCOPE)
        backend.calls.clear()
        streamer.onKioskHome()
        assertEquals(listOf("unpublish estetoscopio", "disconnect"), backend.calls)
        assertEquals(TeleStatus(), streamer.status)
        // Otra vez en el inicio, nada.
        backend.calls.clear()
        streamer.onKioskHome()
        assertEquals(emptyList(), backend.calls)
    }

    @Test
    fun afterThreeHoursTheRoomCloses() {
        streamer.onKioskToken(first)
        backend.calls.clear()
        now += TeleStreamer.MAX_ROOM_MILLIS - 1
        streamer.tick()
        assertEquals(emptyList(), backend.calls)
        now += 1
        streamer.tick()
        assertEquals(listOf("disconnect"), backend.calls)
        assertEquals(RoomState.DISCONNECTED, streamer.status.room)
    }

    @Test
    fun anOrderWithTheLocalPanelOpenIsIgnoredAndTheRoomStays() {
        streamer.onKioskToken(first)
        backend.calls.clear()
        backend.blockedReason = "el Panel Local está abierto"
        start(Instrument.OTOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals(RoomState.CONNECTED, streamer.status.room)
        assertEquals("el Panel Local está abierto", streamer.status.message)
    }

    @Test
    fun anInstrumentThatDoesNotOpenKeepsTheRoom() {
        streamer.onKioskToken(first)
        backend.publishError = "El otoscopio no aparece"
        start(Instrument.OTOSCOPE)
        assertEquals(RoomState.CONNECTED, streamer.status.room)
        assertNull(streamer.status.instrument)
        assertEquals("El otoscopio no aparece", streamer.status.message)
    }

    @Test
    fun aRoomThatDoesNotOpenIsNotRetriedOnEveryLoadOfTheSamePage() {
        backend.connectError = "No se pudo entrar a la sala"
        streamer.onKioskToken(first)
        streamer.onKioskToken(first)
        assertEquals(listOf("connect wss://gvm.livekit.cloud"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
    }

    @Test
    fun aLostRoomIsEnteredAgainOnceWhileTheTokenStillServes() {
        streamer.onKioskToken(first)
        start(Instrument.STETHOSCOPE)
        backend.calls.clear()
        now += 60_000
        streamer.roomLost("signal_close")
        assertEquals(listOf("unpublish estetoscopio", "disconnect", "connect wss://gvm.livekit.cloud"), backend.calls)
        assertEquals(RoomState.CONNECTED, streamer.status.room)
        // La segunda caída ya no reintenta.
        backend.calls.clear()
        streamer.roomLost("signal_close")
        assertEquals(listOf("disconnect"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
    }

    @Test
    fun aLostRoomWithAnOldTokenIsNotEnteredAgain() {
        streamer.onKioskToken(first)
        backend.calls.clear()
        now += TeleStreamer.TOKEN_ENTRY_MILLIS
        streamer.roomLost("signal_close")
        assertEquals(listOf("disconnect"), backend.calls)
        assertEquals(RoomState.ERROR, streamer.status.room)
    }

    @Test
    fun theManualTestIsNotTouchedByOrdersAndTheKioskTokenReplacesIt() {
        streamer.startManual(" ws://10.0.2.2:7880 ", jwt("prueba"), Instrument.STETHOSCOPE)
        assertEquals(listOf("connect ws://10.0.2.2:7880", "publish estetoscopio"), backend.calls)
        assertTrue(streamer.status.manual)
        backend.calls.clear()
        start(Instrument.OTOSCOPE)
        streamer.unpublishAll("fin de la toma de signos")
        streamer.onKioskHome()
        assertEquals(emptyList(), backend.calls)
        streamer.onKioskToken(first)
        assertEquals(listOf("unpublish estetoscopio", "disconnect", "connect wss://gvm.livekit.cloud"), backend.calls)
        assertEquals(false, streamer.status.manual)
    }

    @Test
    fun theManualTestNeedsServerAndToken() {
        streamer.startManual("", "jwt", Instrument.OTOSCOPE)
        assertEquals(emptyList(), backend.calls)
        assertEquals("Faltan el servidor o el token", streamer.status.message)
    }

    @Test
    fun theLocalPanelCutsTheManualTestToo() {
        streamer.startManual("ws://10.0.2.2:7880", jwt("prueba"), Instrument.OTOSCOPE)
        backend.calls.clear()
        streamer.unpublishAll("se abrió el Panel Local", includeManual = true)
        assertEquals(listOf("unpublish otoscopio"), backend.calls)
        streamer.stopManual()
        assertEquals(listOf("unpublish otoscopio", "disconnect"), backend.calls)
    }

    @Test
    fun anExceptionDoesNotEscapeAndReleasesEverything() {
        val failing = object : TeleBackend by backend {
            override fun publish(instrument: Instrument): String? = throw IllegalStateException("roto")
        }
        val streamer = TeleStreamer(failing, log = {}, executor = { it.run() }, clock = { now })
        streamer.onKioskToken(first)
        streamer.order(TeleOrder(Instrument.OTOSCOPE, true, "e1"))
        assertEquals(RoomState.ERROR, streamer.status.room)
        assertEquals("disconnect", backend.calls.last())
    }
}
