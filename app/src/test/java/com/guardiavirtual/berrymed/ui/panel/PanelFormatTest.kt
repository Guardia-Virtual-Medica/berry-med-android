// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.NO_PAIR
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val none = LiveFeed.NibpState(status = -1, cuffMmhg = 0)
        assertEquals("Midiendo · manguito 80 mmHg", PanelFormat.nibpDetail(true, LiveFeed.NibpState(0x04, 80), "—"))
        assertEquals("Sin medir todavía", PanelFormat.nibpDetail(false, none, "—"))
        assertEquals("Última medición terminada", PanelFormat.nibpDetail(false, LiveFeed.NibpState(0x00, 0), "120/80"))
    }

    @Test
    fun aMeasurementThatEndedInErrorIsNotShownAsNeverMeasured() {
        // BIT5~2 = 0x4: manguito flojo, un final de la plaqueta sin presión.
        val looseCuff = LiveFeed.NibpState(status = 0x4 shl 2, cuffMmhg = 0)
        assertTrue(Pm6750Protocol.nibpIsTerminal(looseCuff.status))
        assertEquals("Última medición sin resultado", PanelFormat.nibpDetail(false, looseCuff, "—"))
        // Cortada con Parar mientras inflaba: la plaqueta no llegó a mandar un final.
        val inflating = LiveFeed.NibpState(status = 0x1 shl 2, cuffMmhg = 120)
        assertEquals("Última medición sin resultado", PanelFormat.nibpDetail(false, inflating, "—"))
    }
}
