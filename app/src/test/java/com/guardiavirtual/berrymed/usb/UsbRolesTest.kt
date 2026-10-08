// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbRolesTest {
    private val second = 1_000_000_000L

    @Test
    fun aPendingDialogIsNotAskedTwice() {
        val requests = PermissionRequests(retryAfterNanos = 300 * second)
        assertTrue(requests.shouldAsk(7, now = 0))
        assertFalse(requests.shouldAsk(7, now = 3_600 * second))
    }

    @Test
    fun aDeniedDeviceIsAskedAgainOnlyAfterTheWait() {
        val requests = PermissionRequests(retryAfterNanos = 300 * second)
        requests.shouldAsk(7, now = 0)
        requests.answered(7, granted = false, now = 10 * second)
        assertFalse(requests.shouldAsk(7, now = 200 * second))
        assertTrue(requests.shouldAsk(7, now = 310 * second))
    }

    @Test
    fun aGrantedOrForgottenDeviceCanBeAskedAgainRightAway() {
        val requests = PermissionRequests(retryAfterNanos = 300 * second)
        requests.shouldAsk(7, now = 0)
        requests.answered(7, granted = true, now = second)
        assertTrue(requests.shouldAsk(7, now = 2 * second))

        requests.answered(7, granted = false, now = 3 * second)
        requests.forget()
        assertTrue(requests.shouldAsk(7, now = 4 * second))
    }

    @Test
    fun aDeviceThatLeftIsForgotten() {
        val requests = PermissionRequests(retryAfterNanos = 300 * second)
        requests.shouldAsk(7, now = 0)
        requests.shouldAsk(8, now = 0)
        requests.retain(setOf(8))
        assertTrue(requests.shouldAsk(7, now = second))
        assertFalse(requests.shouldAsk(8, now = second))
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
    fun berryFramesAreToldApartFromThermometerText() {
        val line = """{"valor_temperatura_ir": 36.6, "unit": "C"}""".toByteArray()
        assertFalse(BerryLink.hasHeader(line))
        assertTrue(BerryLink.isThermometer(line))
        assertTrue(BerryLink.hasHeader(byteArrayOf(0x10, 0x55, 0xAA.toByte(), 0x04)))
    }
}
