// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.protocol

import com.guardiavirtual.berrymed.config.buildMetricsUrl
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun commandFrameMatchesTheManualLayout() {
        val frame = Pm6750Protocol.buildCommand(0x01, 0x01)
        assertContentEquals(byteArrayOf(0x55, 0xAA.toByte(), 0x04, 0x01, 0x01, 0xF9.toByte()), frame)
    }

    @Test
    fun startupConfiguresBeforeOpeningStreamsAndSkipsNibp() {
        val pairs = Pm6750Protocol.buildStartupCommands(DeviceTuning())
        assertEquals(
            listOf(0x07 to 0x03, 0x08 to 0x02, 0x0F to 0x03, 0x01 to 1, 0x03 to 1, 0x04 to 1, 0xFE to 1, 0xFF to 1),
            pairs,
        )
        assertEquals(48, Pm6750Protocol.startupBytes(DeviceTuning()).size)
    }

    @Test
    fun invalidGainFallsBackAndNibpTargetIsScaled() {
        val notes = ArrayList<String>()
        val startup = Pm6750Protocol.buildStartupCommands(DeviceTuning(ecgGain = "nope"), notes::add)
        assertEquals(0x03, startup.first().second)
        assertTrue(notes.any { "ECG_GAIN" in it })

        val nibp = Pm6750Protocol.buildNibpCommands(DeviceTuning(nibpMode = "adulto", nibpTargetPressureMmhg = "160"))
        assertEquals(listOf(0x09 to 0x01, 0x0A to 80, 0x02 to 0x01), nibp)

        val ignored = ArrayList<String>()
        val outOfRange = Pm6750Protocol.buildNibpCommands(
            DeviceTuning(nibpMode = "neonato", nibpTargetPressureMmhg = "200"),
            ignored::add,
        )
        assertEquals(listOf(0x09 to 0x03, 0x02 to 0x01), outOfRange)
        assertTrue(ignored.any { "fuera del rango" in it })
    }

    @Test
    fun statsCountFramesByTypeWithoutConsumingThePayload() {
        val parser = BmDataParser()
        parser.addData(frame(0x01, intArrayOf(10, 20, 30, 40, 50, 60, 70)))
        parser.addData(frame(0x01, intArrayOf(11, 21, 31, 41, 51, 61, 71)))
        parser.addData(frame(0xFE, intArrayOf(80)))
        parser.addData(frame(0xFF, intArrayOf(90)))
        parser.addData(frame(0x05, intArrayOf(0, 36, 6)))
        parser.addData(frame(0x03, intArrayOf(0x04, 60, 0, 0, 0)))
        val bad = frame(0x04, intArrayOf(0, 98, 72))
        bad[bad.lastIndex] = (bad.last() + 1).toByte()
        parser.addData(bad)

        val stats = parser.stats()
        assertEquals(2, stats.frames.ecg)
        assertEquals(1, stats.frames.spo2Wave)
        assertEquals(1, stats.frames.respWave)
        assertEquals(1, stats.frames.temperature)
        assertEquals(1, stats.frames.nibp)
        assertEquals(0x04, stats.frames.nibpStatus)
        assertEquals(120, stats.frames.cuffMmhg)
        assertEquals(0, stats.frames.spo2)
        assertEquals(1, stats.frames.badChecksum)
        assertEquals("36.6", stats.vitals.temperature)
        // Mirar los contadores no le saca nada al envío.
        assertEquals(listOf(10, 11), parser.takePayload().ecg.getValue("I"))
        assertEquals(2, parser.stats().frames.ecg)
    }

    @Test
    fun ecgFrameFillsTheSevenLeadsAndTheNextPostIsEmpty() {
        val parser = BmDataParser()
        parser.addData(frame(0x01, intArrayOf(10, 20, 30, 40, 50, 60, 70)))
        parser.addData(frame(0x01, intArrayOf(11, 21, 31, 41, 51, 61, 71)))
        val first = parser.takePayload()
        assertEquals(listOf(10, 11), first.ecg.getValue("I"))
        assertEquals(listOf(70, 71), first.ecg.getValue("V"))
        assertEquals(7, first.ecg.size)
        val second = parser.takePayload()
        assertTrue(second.ecg.values.all { it.isEmpty() })
    }

    @Test
    fun fragmentedBytesAndABadChecksumStillYieldTheGoodFrame() {
        val parser = BmDataParser()
        val good = frame(0xFE, intArrayOf(42))
        val bad = good.copyOf()
        bad[bad.size - 1] = 0
        val stream = byteArrayOf(0x00, 0x11) + bad + good
        parser.addData(stream.copyOfRange(0, 3))
        parser.addData(stream.copyOfRange(3, stream.size))
        val window = parser.takePayload()
        assertEquals(listOf(42), window.spo2)
    }

    @Test
    fun aHeaderAtTheVeryEndOfARead() {
        val parser = BmDataParser()
        val good = frame(0xFE, intArrayOf(42))
        parser.addData(byteArrayOf(0x12, 0x34) + good.copyOfRange(0, 2))
        parser.addData(good.copyOfRange(2, good.size))
        assertEquals(listOf(42), parser.takePayload().spo2)
    }

    @Test
    fun aShortEcgParamsFrameLeavesNoHalfWrittenValue() {
        val parser = BmDataParser()
        parser.addData(frame(0x02, intArrayOf(0x28, 86, 18, 6, 0x01)))
        parser.addData(frame(0x02, intArrayOf(0x28)))
        assertEquals("86", parser.peekVitals().heartRate)
        assertEquals("18", parser.peekVitals().respRate)
    }

    @Test
    fun spo2ErrorDoesNotEraseAGoodPulse() {
        val parser = BmDataParser()
        parser.addData(frame(0x04, intArrayOf(0x00, 127, 72)))
        assertEquals("-/72", parser.peekVitals().spo2Pulse)
        parser.addData(frame(0x04, intArrayOf(0x00, 98, 255)))
        assertEquals("98/-", parser.peekVitals().spo2Pulse)
        parser.addData(frame(0x04, intArrayOf(0x00, 127, 255)))
        assertEquals("- - /- -", parser.peekVitals().spo2Pulse)
        parser.addData(frame(0x04, intArrayOf(0x00, 98, 72)))
        assertEquals("98/72", parser.peekVitals().spo2Pulse)
    }

    @Test
    fun zeroNibpDoesNotReplaceThePreviousReading() {
        val parser = BmDataParser()
        parser.addData(frame(0x03, intArrayOf(0x00, 60, 120, 90, 80)))
        assertEquals("120/80", parser.peekVitals().nibp)
        parser.addData(frame(0x03, intArrayOf(0x04, 40, 0, 0, 0)))
        assertEquals("120/80", parser.peekVitals().nibp)
        parser.resetNibp()
        assertEquals("- - /- -", parser.peekVitals().nibp)
    }

    @Test
    fun ecgParamsPeakAndTemperature() {
        val parser = BmDataParser()
        parser.addData(frame(0x01, intArrayOf(1, 2, 3, 4, 5, 6, 7)))
        parser.addData(frame(0x01, intArrayOf(1, 2, 3, 4, 5, 6, 7)))
        parser.addData(frame(0x30, intArrayOf()))
        // modo diagnóstico (10), ganancia x1 (10): 0b00101000 = 0x28. ST = 6 → 0,06 mV. HRV normal.
        parser.addData(frame(0x02, intArrayOf(0x28, 86, 18, 6, 0x01)))
        parser.addData(frame(0x05, intArrayOf(0x00, 36, 6)))
        val window = parser.takePayload()
        assertEquals("86", window.vitalSigns.heartRate)
        assertEquals("18", window.vitalSigns.respRate)
        assertEquals("36.6", window.vitalSigns.temperature)
        assertEquals(false, window.ecgInfo.leadOff)
        assertEquals("x1", window.ecgInfo.gain)
        assertEquals("diagnostico", window.ecgInfo.mode)
        assertEquals(0.06, window.ecgInfo.st)
        assertEquals("normal", window.ecgInfo.hrv)
        assertEquals(1, window.ecgPeaks.size)
        assertEquals(2, window.ecgPeaks.first().sample)
        assertEquals(8.0, window.ecgPeaks.first().ms)
        val again = parser.takePayload()
        assertEquals("86", again.vitalSigns.heartRate)
        assertTrue(again.ecgPeaks.isEmpty())
    }

    @Test
    fun emptyWindowIsNotPostedButAWaveOrATemperatureIs() {
        val parser = BmDataParser()
        assertFalse(isValidSample(parser.takePayload()))
        parser.addData(frame(0xFE, intArrayOf(0, 0)))
        assertFalse(isValidSample(parser.takePayload()))
        parser.addData(frame(0xFF, intArrayOf(3)))
        assertTrue(isValidSample(parser.takePayload()))
        parser.addData(frame(0x02, intArrayOf(0x00, 0, 0)))
        assertFalse(isValidSample(parser.takePayload()))
    }

    @Test
    fun thermometerOverridesBerryTemperature() {
        assertNull(parseThermometerLine("ruido"))
        assertEquals(ThermoReading(36.5, "C"), parseThermometerLine("""{"valor_temperatura_ir": "36.5", "unit": "C"}"""))
        assertEquals(ThermoReading(36.6, "C"), parseThermometerLine("""{"unit": "C", "valor_temperatura_ir": 36.6}"""))
        assertNull(parseThermometerLine("""{"valor_temperatura_ir": "3, "unit": "C"}"""))
        assertNull(parseThermometerLine("""{"valor_temperatura_ir": "36,5", "unit": "C"}"""))
        val celsius = parseThermometerLine("""{"valor_temperatura_ir": 36.6, "unit": "C"}""")
        assertEquals(ThermoReading(36.6, "C"), celsius)
        val vitals = VitalSigns(temperature = "22.2")
        applyThermometer(vitals, celsius)
        assertEquals("36.6", vitals.temperature)
        applyThermometer(vitals, ThermoReading(98.6, "F"))
        assertEquals("37.0", vitals.temperature)
        applyThermometer(vitals, ThermoReading(0.0, "C"))
        assertEquals("-", vitals.temperature)
        val window = SampleWindow(emptyList(), emptyMap(), emptyList(), EcgInfo(), emptyList(), vitals.copy())
        assertFalse(isValidSample(window))
        vitals.temperature = "36.6"
        assertTrue(isValidSample(window.copy(vitalSigns = vitals)))
    }

    @Test
    fun envelopeUsesTheWindowsFieldNames() {
        val parser = BmDataParser()
        parser.addData(frame(0x02, intArrayOf(0x28, 86, 18, 6, 0x01)))
        parser.addData(frame(0x01, intArrayOf(249, 136, 6, 60, 249, 70, 111)))
        val json = metricsEnvelope(1_757_001_600_000, parser.takePayload())
        assertTrue(json.startsWith("{\"timestamp\":1757001600000,\"data\":{"))
        assertTrue(json.contains("\"heartRate\":\"86\""))
        assertTrue(json.contains("\"I\":[249]"))
        assertTrue(json.contains("\"V\":[111]"))
        assertTrue(json.contains("\"st\":0.06"))
        assertTrue(json.contains("\"hrv\":\"normal\""))
    }

    @Test
    fun aSecondOfDataIsSentAsIs() {
        val window = windowOf(ecgSamples = 250, waveSamples = 50)
        assertEquals(listOf(window), splitWindow(window))
    }

    @Test
    fun aLongerWindowIsSplitIntoSecondsWithoutLosingSamples() {
        val window = windowOf(ecgSamples = 600, waveSamples = 120, peaks = listOf(10, 260, 590))
        val parts = splitWindow(window)
        assertEquals(listOf(250, 250, 100), parts.map { it.ecg.getValue("II").size })
        assertEquals(600, parts.sumOf { it.ecg.getValue("I").size })
        assertEquals(120, parts.sumOf { it.spo2.size })
        assertEquals(120, parts.sumOf { it.resp.size })
        assertEquals(listOf(listOf(10), listOf(10), listOf(90)), parts.map { part -> part.ecgPeaks.map { it.sample } })
        assertEquals(40.0, parts[1].ecgPeaks.single().ms)
        assertTrue(parts.all { it.vitalSigns.heartRate == "86" })
    }

    @Test
    fun eachPartStaysUnderPushersTenKilobytes() {
        val parts = splitWindow(windowOf(ecgSamples = 1_000, waveSamples = 200, value = 249))
        assertTrue(parts.all { metricsEnvelope(1_757_001_600_000, it).length < 10 * 1024 })
    }

    @Test
    fun theEnvelopeStaysValidJsonWithControlCharacters() {
        val window = SampleWindow(
            spo2 = emptyList(),
            ecg = emptyMap(),
            ecgPeaks = emptyList(),
            ecgInfo = EcgInfo(hrv = "a\tb\rc"),
            resp = emptyList(),
            vitalSigns = VitalSigns(),
        )
        val json = metricsEnvelope(0, window)
        assertTrue(json.contains("\"hrv\":\"a\\u0009b\\u000dc\""))
        assertFalse(json.any { it < ' ' })
    }

    @Test
    fun sessionExpiresOnlyAfterTheCap() {
        val start = 1_000L
        val minute = 60L * 1_000_000_000L
        assertFalse(sessionExpired(false, start, start + 31 * minute, 30))
        assertFalse(sessionExpired(true, start, start + 29 * minute, 30))
        assertTrue(sessionExpired(true, start, start + 30 * minute, 30))
        assertFalse(sessionExpired(true, start, start + 90 * minute, 0))
    }

    @Test
    fun metricsUrlJoinsBaseTotemAndEndpoint() {
        assertEquals(
            "https://api.ejemplo.com/v1/medical_devices/totem12/metrics",
            buildMetricsUrl("https://api.ejemplo.com/v1/medical_devices/", "totem12", "metrics"),
        )
    }

    @Test
    fun terminalNibpResultsMatchTheWindowsReader() {
        assertTrue(Pm6750Protocol.nibpIsTerminal(0x00))
        assertTrue(Pm6750Protocol.nibpIsTerminal(0x08)) // detenido, result 0x2
        assertTrue(Pm6750Protocol.nibpIsTerminal(0x10)) // manguito flojo, result 0x4
        assertTrue(Pm6750Protocol.nibpIsTerminal(0x14)) // demoró, result 0x5
        assertFalse(Pm6750Protocol.nibpIsTerminal(0x04)) // midiendo, result 0x1
    }
}

private fun windowOf(ecgSamples: Int, waveSamples: Int, peaks: List<Int> = emptyList(), value: Int = 128): SampleWindow =
    SampleWindow(
        spo2 = List(waveSamples) { value },
        ecg = Pm6750Protocol.LEADS.associateWith { List(ecgSamples) { value } },
        ecgPeaks = peaks.map { EcgPeak(it, it * 4.0) },
        ecgInfo = EcgInfo(),
        resp = List(waveSamples) { value },
        vitalSigns = VitalSigns(heartRate = "86"),
    )

private fun frame(type: Int, data: IntArray): ByteArray {
    val body = ByteArray(1 + data.size)
    body[0] = type.toByte()
    data.forEachIndexed { index, value -> body[index + 1] = value.toByte() }
    val n = body.size + 2
    var sum = n
    for (b in body) sum += b.toInt() and 0xFF
    val out = ByteArray(body.size + 4)
    out[0] = 0x55
    out[1] = 0xAA.toByte()
    out[2] = n.toByte()
    body.copyInto(out, 3)
    out[out.lastIndex] = (sum.inv() and 0xFF).toByte()
    return out
}
