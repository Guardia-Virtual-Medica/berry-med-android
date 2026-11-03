// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.CALL_AUDIO_HEADSET
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubRestStateTest {
    @Test
    fun theThermometerStaysOnWithAndWithoutTheKioskWhateverTheCallAudio() {
        for (output in listOf(CALL_AUDIO_HEADSET, CALL_AUDIO_SPEAKER)) {
            val config = BerryConfig(callAudioOutput = output)
            assertEquals(listOf(false, false, true), HubRestState.wanted(config, kiosk = false))
            assertEquals(listOf(true, true, true), HubRestState.wanted(config, kiosk = true))
        }
    }

    @Test
    fun aFreePortStaysAsItIs() {
        val config = BerryConfig(ykushOtoscopePort = 1, ykushStethoscopePort = 1, ykushThermometerPort = 3)
        assertEquals(listOf(false, null, true), HubRestState.wanted(config, kiosk = false))
        assertEquals(listOf(true, null, true), HubRestState.wanted(config, kiosk = true))
    }

    @Test
    fun theThermometerFollowsItsConfiguredPort() {
        val config = BerryConfig(ykushOtoscopePort = 2, ykushStethoscopePort = 3, ykushThermometerPort = 1)
        assertEquals(listOf(true, false, false), HubRestState.wanted(config, kiosk = false))
        assertEquals(listOf(true, true, true), HubRestState.wanted(config, kiosk = true))
    }

    @Test
    fun withSomeoneUsingADeviceOnlyTheThermometerIsTurnedOn() {
        val rest = HubRestState.wanted(BerryConfig(), kiosk = false)
        assertEquals(
            listOf(3 to true),
            HubRestState.changes(rest, listOf(true, true, false), blocked = true, thermometerPort = 3),
        )
        val kiosk = HubRestState.wanted(BerryConfig(), kiosk = true)
        assertEquals(
            listOf(3 to true),
            HubRestState.changes(kiosk, listOf(false, false, null), blocked = true, thermometerPort = 3),
        )
        assertEquals(
            emptyList(),
            HubRestState.changes(kiosk, listOf(false, false, true), blocked = true, thermometerPort = 3),
        )
        // Sin bloqueo sale todo lo que difiere.
        assertEquals(
            listOf(1 to true, 2 to true, 3 to true),
            HubRestState.changes(kiosk, listOf(false, false, false), blocked = false, thermometerPort = 3),
        )
    }

    @Test
    fun leavingTheKioskTurnsOffOnlyTheOtoscopeAndTheStethoscope() {
        val config = BerryConfig()
        val kiosk = HubRestState.wanted(config, kiosk = true)
        assertEquals(listOf(1 to false, 2 to false), HubRestState.changes(HubRestState.wanted(config, kiosk = false), kiosk))
        assertEquals(
            "puerto 1 encendido, puerto 2 encendido, puerto 3 encendido",
            HubRestState.describe(kiosk),
        )
    }

    @Test
    fun onlyWhatDiffersIsOrderedAndAnUnreadPortIsOrderedAnyway() {
        val wanted = listOf(false, false, true)
        assertEquals(listOf(1 to false), HubRestState.changes(wanted, listOf(true, false, true)))
        assertEquals(listOf(2 to false), HubRestState.changes(wanted, listOf(false, null, true)))
        assertEquals(emptyList(), HubRestState.changes(wanted, listOf(false, false, true)))
    }

    @Test
    fun aFreePortGetsNoOrder() {
        assertEquals(listOf(3 to true), HubRestState.changes(listOf(false, null, true), listOf(false, true, false)))
    }

    @Test
    fun itDescribesOnlyThePortsWithADevice() {
        assertEquals("puerto 1 apagado, puerto 3 encendido", HubRestState.describe(listOf(false, null, true)))
    }

    @Test
    fun itIsDueOncePerHubAndWaitsForAnUsableOne() {
        val pending = HubRestState.Pending()
        assertFalse(pending.due(null))
        assertTrue(pending.due(7))
        pending.done(7)
        assertFalse(pending.due(7))
        assertTrue(pending.due(8))
    }

    @Test
    fun againMakesItDueAgain() {
        val pending = HubRestState.Pending()
        pending.done(7)
        assertFalse(pending.thermometerDue(7))
        pending.again()
        assertTrue(pending.due(7))
        assertTrue(pending.thermometerDue(7))
    }

    @Test
    fun theThermometerIsTurnedOnOncePerHubWhileTheRestWaits() {
        val pending = HubRestState.Pending()
        assertFalse(pending.thermometerDue(null))
        assertTrue(pending.thermometerDue(7))
        pending.thermometerDone(7)
        assertFalse(pending.thermometerDue(7))
        // El resto sigue pendiente para cuando se libere.
        assertTrue(pending.due(7))
        // Un hub reconectado es otro deviceId: se vuelve a encender.
        assertTrue(pending.thermometerDue(8))
    }
}
