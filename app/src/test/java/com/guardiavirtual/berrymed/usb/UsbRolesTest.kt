// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbRolesTest {
    private val second = 1_000_000_000L

    @Test
    fun eachDeviceIsAskedOnceSoTheKioskIsNotCoveredAgainAndAgain() {
        val requests = PermissionRequests()
        assertEquals(7, requests.next(listOf(7), now = 0))
        requests.answered(7)
        assertNull(requests.next(listOf(7), now = 3_600 * second))
        assertEquals(8, requests.next(listOf(7, 8), now = 3_600 * second))
    }

    @Test
    fun withARequestInFlightNoOtherIsAsked() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        assertEquals(1, requests.next(listOf(1, 2, 3), now = 0))
        assertNull(requests.next(listOf(1, 2, 3), now = 30 * second))
    }

    @Test
    fun anAnswerLetsTheNextOneBeAskedAcceptedOrDenied() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2, 3), now = 0)
        // Aceptado: ya no falta.
        requests.answered(1)
        assertEquals(2, requests.next(listOf(2, 3), now = second))
        // Rechazado: sigue faltando, pero no se vuelve a pedir solo.
        requests.answered(2)
        assertEquals(3, requests.next(listOf(2, 3), now = 2 * second))
        requests.answered(3)
        assertNull(requests.next(listOf(2, 3), now = 3 * second))
    }

    @Test
    fun anAnswerWithoutTheDeviceStillFreesTheRequest() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2), now = 0)
        requests.answered(null)
        assertEquals(2, requests.next(listOf(1, 2), now = second))
    }

    @Test
    fun anAnswerForAnotherDeviceDoesNotFreeIt() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2), now = 0)
        requests.answered(9)
        assertNull(requests.next(listOf(1, 2), now = second))
    }

    @Test
    fun anUnansweredRequestExpiresAndTheNextOneIsAsked() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2), now = 0)
        assertNull(requests.next(listOf(1, 2), now = 59 * second))
        assertEquals(2, requests.next(listOf(1, 2), now = 60 * second))
        // El vencido no se vuelve a pedir solo.
        requests.answered(2)
        assertNull(requests.next(listOf(1, 2), now = 61 * second))
    }

    @Test
    fun aDeviceThatLeavesFreesTheRequestInFlight() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.observe(setOf(1, 2), now = 0)
        requests.next(listOf(1, 2), now = 0)
        requests.observe(setOf(2), now = second)
        assertEquals(2, requests.next(listOf(2), now = second))
    }

    @Test
    fun aDeviceThatGetsItsPermissionOtherwiseFreesTheRequest() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2), now = 0)
        // Android se lo dio solo (la app predeterminada): ya no está entre los que faltan.
        assertEquals(2, requests.next(listOf(2), now = second))
    }

    @Test
    fun theButtonAsksAgainInLine() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2), now = 0)
        requests.answered(1)
        requests.next(listOf(1, 2), now = second)
        requests.answered(2)
        assertNull(requests.next(listOf(1, 2), now = 2 * second))
        requests.forget()
        assertEquals(1, requests.next(listOf(1, 2), now = 3 * second))
        assertNull(requests.next(listOf(1, 2), now = 4 * second))
        requests.answered(1)
        assertEquals(2, requests.next(listOf(1, 2), now = 5 * second))
    }

    @Test
    fun theButtonDoesNotAskTwiceTheOneInFlight() {
        val requests = PermissionRequests(timeoutNanos = 60 * second)
        requests.next(listOf(1, 2), now = 0)
        requests.forget()
        assertNull(requests.next(listOf(1, 2), now = second))
        requests.answered(1)
        assertEquals(2, requests.next(listOf(1, 2), now = 2 * second))
    }

    @Test
    fun aDeviceThatComesBackIsAskedAgain() {
        val requests = PermissionRequests()
        requests.observe(setOf(7), now = 0)
        requests.next(listOf(7), now = 0)
        requests.answered(7)
        requests.observe(emptySet(), now = second)
        requests.observe(setOf(7), now = 2 * second)
        assertEquals(7, requests.next(listOf(7), now = 2 * second))
    }

    @Test
    fun aNewDeviceGetsAMomentForTheDefaultGrantBeforeAsking() {
        val requests = PermissionRequests(graceNanos = 3 * second)
        requests.observe(setOf(7), now = 10 * second)
        assertNull(requests.next(listOf(7), now = 11 * second))
        assertEquals(7, requests.next(listOf(7), now = 13 * second))
    }

    @Test
    fun aDeviceThatComesBackStartsItsWaitAgain() {
        val requests = PermissionRequests(graceNanos = 3 * second)
        requests.observe(setOf(7), now = 0)
        requests.observe(emptySet(), now = 5 * second)
        requests.observe(setOf(7), now = 6 * second)
        assertNull(requests.next(listOf(7), now = 7 * second))
        assertEquals(7, requests.next(listOf(7), now = 9 * second))
    }

    @Test
    fun boardThenHubThenThermometer() {
        val ch340 = 0x1A86
        assertEquals(UsbRole.BOARD, UsbRole.of(UsbHub.STM32_VENDOR, 0x5740))
        assertEquals(UsbRole.HUB, UsbRole.of(KnownDevices.YKUSH_VENDOR, KnownDevices.YKUSH3_PRODUCT))
        assertEquals(UsbRole.THERMOMETER, UsbRole.of(ch340, 0x7523))
        // Microchip sin ser el YKUSH3: un puerto serie, el termómetro.
        assertEquals(UsbRole.THERMOMETER, UsbRole.of(KnownDevices.YKUSH_VENDOR, 0x000A))
        assertEquals(UsbRole.OTOSCOPE, UsbRole.of(KnownDevices.OTOSCOPE_VENDOR, KnownDevices.OTOSCOPE_PRODUCT))
        assertEquals(
            listOf(5, 9, 3),
            UsbHub.inAskOrder(listOf(3 to UsbRole.THERMOMETER, 9 to UsbRole.HUB, 5 to UsbRole.BOARD)),
        )
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

    @Test
    fun thePanelReadsEachDeviceFromOneList() {
        val devices = PanelDevices.of(
            listOf(
                KnownDevices.YKUSH_VENDOR to KnownDevices.YKUSH3_PRODUCT,
                KnownDevices.OTOSCOPE_VENDOR to KnownDevices.OTOSCOPE_PRODUCT,
                KnownDevices.LOGITECH_VENDOR to 0x0A44,
            ),
        )
        assertTrue(devices.ykush)
        assertTrue(devices.otoscope)
        assertFalse(devices.stethoAdapter)
        assertFalse(PanelDevices.of(emptyList()).ykush)
    }
}
