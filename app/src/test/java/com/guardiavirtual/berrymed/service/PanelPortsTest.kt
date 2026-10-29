// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelPortsTest {
    @Test
    fun onlyThePortsThatAreOffAreTurnedOn() {
        val ports = PanelPorts()
        assertEquals(listOf(3), ports.toTurnOn(listOf(2, 3), listOf(true, true, false)))
        assertEquals(emptyList(), ports.toTurnOn(listOf(2, 3), listOf(false, true, true)))
        assertEquals(listOf(2, 3), ports.toTurnOn(listOf(2, 3), listOf(true, false, false)))
    }

    @Test
    fun anUnknownStateIsTurnedOnAndASharedPortGoesOnce() {
        val ports = PanelPorts()
        assertEquals(listOf(2, 3), ports.toTurnOn(listOf(2, 3), listOf(null, null, null)))
        assertEquals(listOf(2), ports.toTurnOn(listOf(2, 2), listOf(true, false, true)))
    }

    @Test
    fun closingTurnsOffOnlyWhatThePanelTurnedOnAndOnce() {
        val ports = PanelPorts()
        assertTrue(ports.claim(3, previousState = false))
        assertEquals(listOf(3), ports.takeToTurnOff())
        assertEquals(emptyList(), ports.takeToTurnOff())
    }

    @Test
    fun ifEverythingWasAlreadyOnNothingIsTurnedOff() {
        val ports = PanelPorts()
        val states = listOf(true, true, true)
        for (port in ports.toTurnOn(listOf(2, 3), states)) ports.claim(port, states[port - 1])
        assertEquals(emptyList(), ports.takeToTurnOff())
    }

    @Test
    fun aPortWhoseStateCouldNotBeReadIsTurnedOnButNotTurnedOffOnClose() {
        // El hub no contestó la lectura del puerto 1: pudo estar encendido desde el arranque.
        val ports = PanelPorts()
        val states = listOf(null, false, true)
        assertEquals(listOf(1, 2), ports.toTurnOn(listOf(1, 2), states))
        assertFalse(ports.claim(1, states[0]))
        assertTrue(ports.claim(2, states[1]))
        assertEquals(listOf(2), ports.takeToTurnOff())
    }
}
