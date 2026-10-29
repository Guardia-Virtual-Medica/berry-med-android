// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import kotlin.test.Test
import kotlin.test.assertEquals

class PanelPortsTest {
    @Test
    fun onlyThePortsThatAreOffAreTurnedOn() {
        val ports = PanelPorts()
        assertEquals(listOf(3), ports.toTurnOn(listOf(2, 3), listOf(true, true, false)))
        assertEquals(emptyList(), ports.toTurnOn(listOf(2, 3), listOf(false, true, true)))
        assertEquals(listOf(2, 3), ports.toTurnOn(listOf(2, 3), listOf(true, false, false)))
    }

    @Test
    fun anUnknownStateIsTreatedAsOffAndASharedPortGoesOnce() {
        val ports = PanelPorts()
        assertEquals(listOf(2, 3), ports.toTurnOn(listOf(2, 3), listOf(null, null, null)))
        assertEquals(listOf(2), ports.toTurnOn(listOf(2, 2), listOf(true, false, true)))
    }

    @Test
    fun closingTurnsOffOnlyWhatThePanelTurnedOnAndOnce() {
        val ports = PanelPorts()
        ports.turnedOn(3)
        assertEquals(listOf(3), ports.takeToTurnOff())
        assertEquals(emptyList(), ports.takeToTurnOff())
    }

    @Test
    fun ifEverythingWasAlreadyOnNothingIsTurnedOff() {
        val ports = PanelPorts()
        for (port in ports.toTurnOn(listOf(2, 3), listOf(true, true, true))) ports.turnedOn(port)
        assertEquals(emptyList(), ports.takeToTurnOff())
    }
}
