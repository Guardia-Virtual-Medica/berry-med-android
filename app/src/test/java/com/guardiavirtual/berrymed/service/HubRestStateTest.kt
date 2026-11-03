// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubRestStateTest {
    @Test
    fun withTheHeadsetOnlyTheHeadsetPortStaysOn() {
        assertEquals(listOf(false, false, true), HubRestState.wanted(BerryConfig(), kiosk = false))
    }

    @Test
    fun withTheSpeakerEveryPortRestsOff() {
        assertEquals(
            listOf(false, false, false),
            HubRestState.wanted(BerryConfig(callAudioOutput = CALL_AUDIO_SPEAKER), kiosk = false),
        )
    }

    @Test
    fun aFreePortStaysAsItIs() {
        val config = BerryConfig(ykushOtoscopePort = 1, ykushStethoscopePort = 1, ykushHeadsetPort = 3)
        assertEquals(listOf(false, null, true), HubRestState.wanted(config, kiosk = false))
    }

    @Test
    fun theHeadsetFollowsItsConfiguredPort() {
        val config = BerryConfig(ykushOtoscopePort = 2, ykushStethoscopePort = 3, ykushHeadsetPort = 1)
        assertEquals(listOf(true, false, false), HubRestState.wanted(config, kiosk = false))
    }

    @Test
    fun withTheKioskTheOtoscopeAndTheStethoscopeStayOnWithTheHeadset() {
        assertEquals(listOf(true, true, true), HubRestState.wanted(BerryConfig(), kiosk = true))
    }

    @Test
    fun withTheKioskAndTheSpeakerOnlyTheHeadsetIsOff() {
        assertEquals(
            listOf(true, true, false),
            HubRestState.wanted(BerryConfig(callAudioOutput = CALL_AUDIO_SPEAKER), kiosk = true),
        )
    }

    @Test
    fun withTheKioskAFreePortStaysAsItIs() {
        val config = BerryConfig(ykushOtoscopePort = 1, ykushStethoscopePort = 1, ykushHeadsetPort = 3)
        assertEquals(listOf(true, null, true), HubRestState.wanted(config, kiosk = true))
        val speaker = config.copy(callAudioOutput = CALL_AUDIO_SPEAKER)
        assertEquals(listOf(true, null, false), HubRestState.wanted(speaker, kiosk = true))
    }

    @Test
    fun withTheKioskTheHeadsetFollowsItsConfiguredPort() {
        val config = BerryConfig(ykushOtoscopePort = 2, ykushStethoscopePort = 3, ykushHeadsetPort = 1)
        assertEquals(listOf(true, true, true), HubRestState.wanted(config, kiosk = true))
        assertEquals(
            listOf(false, true, true),
            HubRestState.wanted(config.copy(callAudioOutput = CALL_AUDIO_SPEAKER), kiosk = true),
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
    fun aNewAudioOutputMakesItDueAgain() {
        val pending = HubRestState.Pending()
        pending.done(7)
        pending.again()
        assertTrue(pending.due(7))
    }
}
