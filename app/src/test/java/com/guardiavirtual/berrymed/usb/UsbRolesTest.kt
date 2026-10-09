// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbRolesTest {
    private val second = 1_000_000_000L

    @Test
    fun eachDeviceIsAskedOnceSoChromeIsNotCoveredAgainAndAgain() {
        val requests = PermissionRequests()
        assertTrue(requests.shouldAsk(7, now = 0))
        assertFalse(requests.shouldAsk(7, now = 3_600 * second))
        assertTrue(requests.shouldAsk(8, now = 3_600 * second))
    }

    @Test
    fun theButtonAsksAgain() {
        val requests = PermissionRequests()
        requests.shouldAsk(7, now = 0)
        requests.forget()
        assertTrue(requests.shouldAsk(7, now = second))
    }

    @Test
    fun aDeviceThatComesBackIsAskedAgain() {
        val requests = PermissionRequests()
        requests.shouldAsk(7, now = 0)
        requests.shouldAsk(8, now = 0)
        requests.observe(setOf(8), now = second)
        assertFalse(requests.shouldAsk(8, now = second))
        requests.observe(setOf(7, 8), now = 2 * second)
        assertTrue(requests.shouldAsk(7, now = 2 * second))
    }

    @Test
    fun aNewDeviceGetsAMomentForTheDefaultGrantBeforeAsking() {
        val requests = PermissionRequests(graceNanos = 3 * second)
        requests.observe(setOf(7), now = 10 * second)
        assertFalse(requests.shouldAsk(7, now = 11 * second))
        assertTrue(requests.shouldAsk(7, now = 13 * second))
    }

    @Test
    fun aDeviceThatComesBackStartsItsWaitAgain() {
        val requests = PermissionRequests(graceNanos = 3 * second)
        requests.observe(setOf(7), now = 0)
        requests.observe(emptySet(), now = 5 * second)
        requests.observe(setOf(7), now = 6 * second)
        assertFalse(requests.shouldAsk(7, now = 7 * second))
        assertTrue(requests.shouldAsk(7, now = 9 * second))
    }

    @Test
    fun theThermometerNeverTakesTheStPortEvenIfItIsMute() {
        assertFalse(UsbHub.thermometerMayTake(UsbHub.STM32_VENDOR, provenNotBerry = true, berryUp = true))
        assertFalse(UsbHub.thermometerMayTake(UsbHub.STM32_VENDOR, provenNotBerry = false, berryUp = true))
    }

    @Test
    fun theThermometerTakesAProvenPortOrAnyOtherOnceTheBerryIsUp() {
        val ch340 = 0x1A86
        assertTrue(UsbHub.thermometerMayTake(ch340, provenNotBerry = true, berryUp = false))
        assertTrue(UsbHub.thermometerMayTake(ch340, provenNotBerry = false, berryUp = true))
        assertFalse(UsbHub.thermometerMayTake(ch340, provenNotBerry = false, berryUp = false))
    }

    @Test
    fun onlyAMuteStPortTriggersTheRelay() {
        assertTrue(BerryLink.muteMeansHungBerry(UsbHub.STM32_VENDOR))
        assertFalse(BerryLink.muteMeansHungBerry(0x1A86))
        assertFalse(BerryLink.muteMeansHungBerry(0x10C4))
    }

    @Test
    fun theRelayWaitsAMinuteBetweenCuts() {
        assertTrue(BerryLink.relayCooldownOver(lastCutAt = 0L, now = 5 * second))
        assertFalse(BerryLink.relayCooldownOver(lastCutAt = 100 * second, now = 159 * second))
        assertTrue(BerryLink.relayCooldownOver(lastCutAt = 100 * second, now = 160 * second))
    }

    @Test
    fun theRelayGetsEightBytesWithTheCommandFirst() {
        val on = UsbRelay.command(relay = 1, enabled = true)
        val off = UsbRelay.command(relay = 1, enabled = false)
        assertEquals(8, on.size)
        assertEquals(0xFF.toByte(), on[0])
        assertEquals(1.toByte(), on[1])
        assertEquals(0xFD.toByte(), off[0])
    }

    @Test
    fun theRelayStateIsOneBitPerRelayInTheLastByte() {
        val status = byteArrayOf(0x42, 0x41, 0x42, 0x43, 0x44, 0, 0, 0b01)
        assertTrue(UsbRelay.relayIsOn(status, relay = 1))
        assertFalse(UsbRelay.relayIsOn(status, relay = 2))
        status[7] = 0b10
        assertFalse(UsbRelay.relayIsOn(status, relay = 1))
        assertTrue(UsbRelay.relayIsOn(status, relay = 2))
    }

    @Test
    fun aBoardMissingFromTheBusIsCutAfterThreeMinutesThenEveryHalfHour() {
        val minute = 60 * second
        assertFalse(BerryLink.absentCutDue(seenAt = 0, lastAbsentCutAt = 0, now = 2 * minute))
        assertTrue(BerryLink.absentCutDue(seenAt = 0, lastAbsentCutAt = 0, now = 3 * minute))
        assertFalse(BerryLink.absentCutDue(seenAt = 0, lastAbsentCutAt = 3 * minute, now = 32 * minute))
        assertTrue(BerryLink.absentCutDue(seenAt = 0, lastAbsentCutAt = 3 * minute, now = 33 * minute))
        assertFalse(BerryLink.absentCutDue(seenAt = 40 * minute, lastAbsentCutAt = 3 * minute, now = 41 * minute))
    }

    @Test
    fun aPressureThatTakesLongToStartInflatingIsNotCut() {
        assertFalse(BerryLink.nibpWentQuiet(lastFrameAt = 0, now = 170 * second))
    }

    @Test
    fun aPressureMeasurementThatStopsReportingIsOver() {
        assertFalse(BerryLink.nibpWentQuiet(lastFrameAt = 40 * second, now = 69 * second))
        assertTrue(BerryLink.nibpWentQuiet(lastFrameAt = 40 * second, now = 71 * second))
    }

    @Test
    fun berryFramesAreToldApartFromThermometerText() {
        val line = """{"valor_temperatura_ir": 36.6, "unit": "C"}""".toByteArray()
        assertFalse(BerryLink.hasHeader(line))
        assertTrue(BerryLink.isThermometer(line))
        assertTrue(BerryLink.hasHeader(byteArrayOf(0x10, 0x55, 0xAA.toByte(), 0x04)))
    }
}
