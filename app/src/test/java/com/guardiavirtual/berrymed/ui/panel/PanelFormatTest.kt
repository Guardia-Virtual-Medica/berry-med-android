// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.NO_PAIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PanelFormatTest {
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
    fun berryLineSaysHowOldTheLastDataIs() {
        assertEquals("Plaqueta sin conectar", PanelFormat.berryLine(false, 0.2))
        assertEquals("Plaqueta conectada · último dato hace 0,2 s", PanelFormat.berryLine(true, 0.2))
        assertEquals("Plaqueta conectada · sin datos hace 7 s", PanelFormat.berryLine(true, 7.4))
    }

    @Test
    fun electrodesAndArrhythmia() {
        assertEquals("Electrodos sueltos", PanelFormat.electrodes(EcgInfo(leadOff = true)))
        assertEquals("Sin señal de ECG", PanelFormat.electrodes(EcgInfo()))
        assertNull(PanelFormat.arrhythmia("analizando"))
        assertNull(PanelFormat.arrhythmia(null))
        assertEquals("fibrilacion ventricular", PanelFormat.arrhythmia("fibrilacion_ventricular"))
    }

    @Test
    fun thermometerLine() {
        assertEquals("Termómetro IR: sin conectar", PanelFormat.thermometer(false, "36.8", 5))
        assertEquals("Termómetro IR: apretá el gatillo para medir", PanelFormat.thermometer(true, null, null))
        assertEquals("Termómetro IR: 36,8 °C · hace 5 s", PanelFormat.thermometer(true, "36.8", 5))
    }

    @Test
    fun nibpDetail() {
        assertEquals("Midiendo · manguito 80 mmHg", PanelFormat.nibpDetail(true, 80, "—"))
        assertEquals("Sin medir todavía", PanelFormat.nibpDetail(false, 0, "—"))
        assertEquals("Última medición terminada", PanelFormat.nibpDetail(false, 0, "120/80"))
    }
}
