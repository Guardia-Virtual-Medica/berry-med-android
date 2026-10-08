package com.guardiavirtual.berrymed.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BmDataParserTest {

    @Test
    fun ecgWaveform_sevenLeads() {
        val parser = BmDataParser()
        parser.addData(BmDataParser.frame(0x01, 128, 136, 6, 60, 249, 70, 111))
        val payload = parser.takePayload()
        assertEquals(1, payload.ecg["I"]?.size)
        assertEquals(128, payload.ecg["I"]?.first())
        assertEquals(111, payload.ecg["V"]?.first())
    }

    @Test
    fun spo2Error_doesNotClearGoodPulse() {
        val parser = BmDataParser()
        // status=0, spo2=127 (error), pulse=83
        parser.addData(BmDataParser.frame(0x04, 0x00, 127, 83))
        assertEquals("-/83", parser.vitalSigns.spo2Pulse)
    }

    @Test
    fun nibpZero_doesNotOverwritePrevious() {
        val parser = BmDataParser()
        parser.addData(BmDataParser.frame(0x03, 0x00, 0, 115, 80, 52))
        assertEquals("115/52", parser.vitalSigns.nibp)
        parser.addData(BmDataParser.frame(0x03, 0x04, 50, 0, 0, 0))
        assertEquals("115/52", parser.vitalSigns.nibp)
    }

    @Test
    fun ecgPeak_indexedInWindow() {
        val parser = BmDataParser()
        repeat(3) {
            parser.addData(BmDataParser.frame(0x01, 128, 128, 128, 128, 128, 128, 128))
        }
        parser.addData(BmDataParser.frame(0x30))
        val payload = parser.takePayload()
        assertEquals(1, payload.ecgPeaks.size)
        assertEquals(3, payload.ecgPeaks[0].sample)
    }

    @Test
    fun payloadValidator_emptyRejected() {
        val empty = MetricsData(
            spo2 = emptyList(),
            ecg = mapOf("I" to emptyList()),
            ecgPeaks = emptyList(),
            ecgInfo = EcgInfo(null, null, null, null, null, null),
            resp = emptyList(),
            vitalSigns = VitalSigns(),
        )
        assertFalse(PayloadValidator.isValid(empty))
    }

    @Test
    fun payloadValidator_temperatureCounts() {
        val data = MetricsData(
            spo2 = emptyList(),
            ecg = mapOf("I" to emptyList()),
            ecgPeaks = emptyList(),
            ecgInfo = EcgInfo(null, null, null, null, null, null),
            resp = emptyList(),
            vitalSigns = VitalSigns(temperature = "36.5"),
        )
        assertTrue(PayloadValidator.isValid(data))
    }

    @Test
    fun pm6750_buildStartup_includesEnables() {
        val cmds = Pm6750Protocol.buildStartupCommands()
        assertTrue(cmds.any { it.first == Pm6750Protocol.CMD_ECG && it.second == 1 })
        assertTrue(cmds.any { it.first == Pm6750Protocol.CMD_SPO2_WAVE })
    }
}
