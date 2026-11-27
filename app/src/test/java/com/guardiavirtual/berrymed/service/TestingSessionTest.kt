// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.i18n.es
import com.guardiavirtual.berrymed.i18n.inLanguage
import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.FrameCounts
import com.guardiavirtual.berrymed.protocol.ParserStats
import com.guardiavirtual.berrymed.protocol.VitalSigns
import com.guardiavirtual.berrymed.service.TestingSession.Measure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestingSessionTest {
    private val second = 1_000_000_000L

    @Test
    fun ecgPassesWithFullRateAndElectrodesOn() {
        val verdict = TestingSession.verdict(Measure.Ecg, stats(), stats(ecg = 1_250, leadOff = false, hr = "72"), 5.0)
        assertEquals(true, verdict.ok)
        assertTrue("250 muestras/s" in es(verdict.text))
    }

    @Test
    fun ecgFailsWhenSamplesAreMissingOrElectrodesAreOff() {
        assertEquals("No llega la onda de ECG", es(TestingSession.verdict(Measure.Ecg, stats(), stats(), 5.0).text))
        val slow = TestingSession.verdict(Measure.Ecg, stats(), stats(ecg = 500), 5.0)
        assertEquals(false, slow.ok)
        assertTrue("100 muestras/s" in es(slow.text))
        val loose = TestingSession.verdict(Measure.Ecg, stats(), stats(ecg = 1_250, leadOff = true), 5.0)
        assertEquals("Electrodos sueltos", es(loose.text))
    }

    @Test
    fun spo2NeedsWaveValuesAndAFinger() {
        assertEquals(false, TestingSession.verdict(Measure.Spo2, stats(), stats(spo2 = 5), 5.0).ok)
        val noFinger = TestingSession.verdict(Measure.Spo2, stats(), stats(spo2Wave = 250, spo2 = 5), 5.0)
        assertEquals("Sin dedo en el sensor", es(noFinger.text))
        val ok = TestingSession.verdict(Measure.Spo2, stats(), stats(spo2Wave = 250, spo2 = 5, spo2Pulse = "98/72"), 5.0)
        assertEquals(true, ok.ok)
    }

    @Test
    fun respAndTemperatureNeedTheirFrames() {
        assertEquals(false, TestingSession.verdict(Measure.Resp, stats(), stats(), 5.0).ok)
        assertEquals(true, TestingSession.verdict(Measure.Resp, stats(), stats(respWave = 600), 5.0).ok)
        val noSensor = TestingSession.verdict(Measure.Temp, stats(), stats(temperature = 5, temp = "-"), 5.0)
        assertEquals("Sensor de temperatura sin conectar", es(noSensor.text))
        assertEquals(true, TestingSession.verdict(Measure.Temp, stats(), stats(temperature = 5, temp = "36.4"), 5.0).ok)
    }

    @Test
    fun checkRunsFiveSecondsThenReportsAndStops() {
        var now = 0L
        val session = TestingSession { now }
        session.check(Measure.Resp, stats())
        assertNull(session.poll(stats(respWave = 100), berryUp = true).getValue(Measure.Resp).ok)
        now = 5 * second
        assertEquals(true, session.poll(stats(respWave = 600), berryUp = true).getValue(Measure.Resp).ok)
        // Terminada, la prueba no se vuelve a evaluar con datos nuevos.
        now = 10 * second
        assertEquals(true, session.poll(stats(respWave = 600), berryUp = true).getValue(Measure.Resp).ok)
    }

    @Test
    fun checkFailsAtOnceWithoutTheBoard() {
        val session = TestingSession { 0L }
        session.check(Measure.Ecg, stats())
        val verdict = session.poll(stats(), berryUp = false).getValue(Measure.Ecg)
        assertEquals("Plaqueta sin conectar", es(verdict.text))
    }

    @Test
    fun ratesComeFromTheCounterDifference() {
        var now = 0L
        val session = TestingSession { now }
        session.poll(stats(ecg = 1_000, spo2Wave = 10), berryUp = true)
        now = second
        session.poll(stats(ecg = 1_250, spo2Wave = 60, badChecksum = 2), berryUp = true)
        val rates = session.rates()
        assertEquals(250, rates.ecg)
        assertEquals(50, rates.spo2Wave)
        assertEquals(2, rates.badChecksum)
        session.reset()
        assertEquals(0, session.rates().ecg)
        assertFalse(session.poll(stats(), berryUp = true).containsKey(Measure.Ecg))
    }

    private fun stats(
        ecg: Long = 0,
        spo2Wave: Long = 0,
        spo2: Long = 0,
        respWave: Long = 0,
        temperature: Long = 0,
        badChecksum: Long = 0,
        leadOff: Boolean? = null,
        hr: String = "- -",
        spo2Pulse: String = "- - /- -",
        temp: String = "- -",
    ) = ParserStats(
        FrameCounts(
            ecg = ecg,
            spo2Wave = spo2Wave,
            spo2 = spo2,
            respWave = respWave,
            temperature = temperature,
            badChecksum = badChecksum,
        ),
        VitalSigns(heartRate = hr, spo2Pulse = spo2Pulse, temperature = temp),
        EcgInfo(leadOff = leadOff),
    )

    /** El mismo veredicto leído en inglés y en portugués. */
    @Test
    fun theSameVerdictsInOtherLanguages() {
        val none = TestingSession.verdict(Measure.Ecg, stats(), stats(), 5.0).text
        assertEquals("No ECG wave coming in", none.inLanguage("en"))
        assertEquals("A onda do ECG não chega", none.inLanguage("pt"))
        val loose = TestingSession.verdict(Measure.Ecg, stats(), stats(ecg = 1_250, leadOff = true), 5.0).text
        assertEquals("Leads off", loose.inLanguage("en"))
        assertEquals("Eletrodos soltos", loose.inLanguage("pt"))
        val spo2 = TestingSession.verdict(Measure.Spo2, stats(), stats(spo2Wave = 250, spo2 = 5, spo2Pulse = "98/72"), 5.0).text
        assertEquals("OK, SpO₂/pulse 98/72", spo2.inLanguage("en"))
        assertEquals("OK, SpO₂/pulso 98/72", spo2.inLanguage("pt"))
    }
}
