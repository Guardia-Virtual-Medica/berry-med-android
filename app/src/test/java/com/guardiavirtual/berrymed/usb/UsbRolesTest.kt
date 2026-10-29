// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import kotlin.test.Test
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
    fun onlyAMuteStPortIsStillTheBerry() {
        assertTrue(BerryLink.isStPort(UsbHub.STM32_VENDOR))
        assertFalse(BerryLink.isStPort(0x1A86))
        assertFalse(BerryLink.isStPort(0x10C4))
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
    fun aBoardSilentForFiveSecondsHasStaleValues() {
        assertFalse(BerryLink.valuesAreStale(4.9))
        assertTrue(BerryLink.valuesAreStale(5.0))
        assertTrue(BerryLink.STALE_SECONDS < BerryLink.SILENCE_SECONDS)
    }

    @Test
    fun berryFramesAreToldApartFromThermometerText() {
        val line = """{"valor_temperatura_ir": 36.6, "unit": "C"}""".toByteArray()
        assertFalse(BerryLink.hasHeader(line))
        assertTrue(BerryLink.isThermometer(line))
        assertTrue(BerryLink.hasHeader(byteArrayOf(0x10, 0x55, 0xAA.toByte(), 0x04)))
    }
}
