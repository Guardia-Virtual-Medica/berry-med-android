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
        assertEquals(listOf(false, false, true), HubRestState.wanted(BerryConfig()))
    }

    @Test
    fun withTheSpeakerEveryPortRestsOff() {
        assertEquals(
            listOf(false, false, false),
            HubRestState.wanted(BerryConfig(callAudioOutput = CALL_AUDIO_SPEAKER)),
        )
    }

    @Test
    fun aFreePortStaysAsItIs() {
        val config = BerryConfig(ykushOtoscopePort = 1, ykushStethoscopePort = 1, ykushHeadsetPort = 3)
        assertEquals(listOf(false, null, true), HubRestState.wanted(config))
    }

    @Test
    fun theHeadsetFollowsItsConfiguredPort() {
        val config = BerryConfig(ykushOtoscopePort = 2, ykushStethoscopePort = 3, ykushHeadsetPort = 1)
        assertEquals(listOf(true, false, false), HubRestState.wanted(config))
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
