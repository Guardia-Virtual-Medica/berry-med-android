// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentPortsTest {
    // Otoscopio en el 1, estetoscopio en el 2, termómetro en el 3, como de fábrica.
    private fun otoscope(states: List<Boolean?>) = InstrumentPorts.forUse(use = 1, other = 2, thermometer = 3, states)

    private fun stethoscope(states: List<Boolean?>) = InstrumentPorts.forUse(use = 2, other = 1, thermometer = 3, states)

    @Test
    fun fromRestOnlyItsOwnPortIsTurnedOn() {
        assertEquals(InstrumentPorts.Steps(off = emptyList(), on = 1), otoscope(listOf(false, false, true)))
        assertEquals(InstrumentPorts.Steps(off = emptyList(), on = 2), stethoscope(listOf(false, false, true)))
    }

    @Test
    fun theOtherOneIsTurnedOffFirst() {
        assertEquals(InstrumentPorts.Steps(off = listOf(1), on = 2), stethoscope(listOf(true, false, true)))
        assertEquals(InstrumentPorts.Steps(off = listOf(2), on = 1), otoscope(listOf(false, true, true)))
    }

    @Test
    fun alreadyOnAloneChangesNothing() {
        val steps = stethoscope(listOf(false, true, true))
        assertEquals(InstrumentPorts.Steps(off = emptyList(), on = null), steps)
        assertFalse(steps.changes)
    }

    @Test
    fun bothOnTurnsOffTheOtherAndKeepsItsOwn() {
        val steps = stethoscope(listOf(true, true, true))
        assertEquals(InstrumentPorts.Steps(off = listOf(1), on = null), steps)
        assertTrue(steps.changes)
    }

    @Test
    fun anUnreadPortIsTurnedOffIfItIsTheOtherAndOnIfItIsItsOwn() {
        assertEquals(InstrumentPorts.Steps(off = listOf(1), on = 2), stethoscope(listOf(null, null, null)))
        assertEquals(InstrumentPorts.Steps(off = listOf(1), on = 2), stethoscope(emptyList()))
    }

    @Test
    fun theThermometerAndASharedPortAreNeverTurnedOff() {
        assertEquals(
            InstrumentPorts.Steps(off = emptyList(), on = 1),
            InstrumentPorts.forUse(use = 1, other = 3, thermometer = 3, listOf(false, false, true)),
        )
        assertEquals(
            InstrumentPorts.Steps(off = emptyList(), on = null),
            InstrumentPorts.forUse(use = 1, other = 1, thermometer = 3, listOf(true, false, true)),
        )
    }
}
