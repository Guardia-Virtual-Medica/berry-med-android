// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeleOrdersTest {
    private val body = """{"encounter_external_id":"9b2f6c1e-1a2b-4c3d-8e9f-001122334455","totem_id":"t-1"}"""

    @Test
    fun theFourEventsMapToTheirInstrumentAndDirection() {
        assertEquals(TeleOrder(Instrument.OTOSCOPE, true, "9b2f6c1e-1a2b-4c3d-8e9f-001122334455"), TeleOrders.parse("start_oth", body))
        assertEquals(TeleOrder(Instrument.OTOSCOPE, false, "9b2f6c1e-1a2b-4c3d-8e9f-001122334455"), TeleOrders.parse("stop_oth", body))
        assertEquals(TeleOrder(Instrument.STETHOSCOPE, true, "9b2f6c1e-1a2b-4c3d-8e9f-001122334455"), TeleOrders.parse("start_steth", body))
        assertEquals(TeleOrder(Instrument.STETHOSCOPE, false, "9b2f6c1e-1a2b-4c3d-8e9f-001122334455"), TeleOrders.parse("stop_steth", body))
    }

    @Test
    fun aBodyEncodedTwiceIsUnwrapped() {
        val twice = "\"" + body.replace("\"", "\\\"") + "\""
        assertEquals("9b2f6c1e-1a2b-4c3d-8e9f-001122334455", TeleOrders.parse("start_steth", twice)?.encounterId)
    }

    @Test
    fun withoutTheEncounterTheOrderIsIgnored() {
        assertNull(TeleOrders.parse("start_oth", """{"totem_id":"t-1"}"""))
        assertNull(TeleOrders.parse("start_oth", """{"encounter_external_id":"  "}"""))
        assertNull(TeleOrders.parse("start_oth", """{"encounter_external_id":{"id":1}}"""))
        assertNull(TeleOrders.parse("start_oth", "no es json"))
        assertNull(TeleOrders.parse("start_oth", null))
        assertNull(TeleOrders.parse("start_oth", "[]"))
    }

    @Test
    fun otherEventsAreNotInstrumentOrders() {
        assertNull(TeleOrders.parse("start-monitoring", body))
        assertNull(TeleOrders.parse("start-blood-pressure", body))
    }
}
