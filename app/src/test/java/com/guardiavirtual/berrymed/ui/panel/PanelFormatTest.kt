// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.protocol.NO_PAIR
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PanelFormatTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(2026, 10, 29, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theBoardDashesShowAsALongDash() {
        for (raw in listOf("-", "- -", NO_PAIR, "- -/- -", "", null)) assertEquals("—", PanelFormat.value(raw))
        assertEquals("72", PanelFormat.value("72"))
        assertEquals("36,6", PanelFormat.value("36.6"))
    }

    @Test
    fun spo2AndPulseAreSplit() {
        assertEquals("98" to "72", PanelFormat.pair("98/72"))
        assertEquals("—" to "72", PanelFormat.pair("-/72"))
        assertEquals("—" to "—", PanelFormat.pair(NO_PAIR))
    }

    @Test
    fun pressureNeedsBothValues() {
        assertEquals("120/80", PanelFormat.pressure("120/80"))
        assertEquals("—", PanelFormat.pressure(NO_PAIR))
        assertEquals("—", PanelFormat.pressure("120/-"))
    }

    @Test
    fun meanPressureIsOneThirdSystolicTwoThirdsDiastolic() {
        assertEquals(93, PanelFormat.meanPressure("120/80"))
        // (116 + 158) / 3 = 91,33: se redondea.
        assertEquals(91, PanelFormat.meanPressure("116/79"))
        assertEquals(97, PanelFormat.meanPressure("125/83"))
        assertNull(PanelFormat.meanPressure(NO_PAIR))
    }

    @Test
    fun arrhythmia() {
        assertNull(PanelFormat.arrhythmia("analizando"))
        assertNull(PanelFormat.arrhythmia(null))
        assertEquals("fibrilacion ventricular", PanelFormat.arrhythmia("fibrilacion_ventricular"))
    }

    @Test
    fun clockAndDateInArgentineSpanish() {
        assertEquals("10:07", PanelFormat.clock(at(10, 7), zone))
        assertEquals("09:05", PanelFormat.clock(at(9, 5), zone))
        assertEquals("10:12:09", PanelFormat.clockSeconds(at(10, 12, 9), zone))
        assertEquals("Jueves 29 de octubre de 2026", PanelFormat.date(at(10, 12), zone))
    }

    @Test
    fun agoSaysRecienUnderAMinute() {
        val now = at(10, 12)
        assertEquals("recién", PanelFormat.ago(now, now - 59_000))
        assertEquals("hace 1 min", PanelFormat.ago(now, now - 60_000))
        assertEquals("hace 4 min", PanelFormat.ago(now, at(10, 7, 30)))
        assertEquals("hace 2 h", PanelFormat.ago(now, at(8, 0)))
        // Un reloj que vuelve atrás no da minutos negativos.
        assertEquals("recién", PanelFormat.ago(now, now + 5_000))
    }
}
