// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import com.guardiavirtual.berrymed.otoscope.OtoscopePower.Action
import com.guardiavirtual.berrymed.otoscope.OtoscopePower.Path
import com.guardiavirtual.berrymed.otoscope.OtoscopePower.Poll
import com.guardiavirtual.berrymed.otoscope.OtoscopePower.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OtoscopePowerTest {
    private val second = 1_000_000_000L
    private fun power() = OtoscopePower(waitNanos = 8 * second, camera2GraceNanos = 2 * second)

    @Test
    fun turningOnGoesThroughTheStatesInOrder() {
        val power = power()
        assertEquals(State.OFF, power.state)
        assertEquals(listOf(Action.PORT_ON), power.switchOn(hubPresent = true, now = 0))
        assertEquals(State.POWERING_PORT, power.state)
        assertEquals(true, power.portReady(now = second))
        assertEquals(State.WAITING_CAMERA, power.state)
        assertEquals(Poll.Waiting, power.poll(now = 2 * second, cameraSeen = false, usbReady = false))
        assertEquals(Poll.Open(Path.CAMERA2), power.poll(now = 3 * second, cameraSeen = true, usbReady = true))
        assertEquals(State.ON, power.state)
        assertEquals(Path.CAMERA2, power.path)
    }

    @Test
    fun ifTheCameraNeverAppearsItGoesBackToOffAndAsksToTurnThePortOff() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        power.portReady(now = 0)
        assertEquals(Poll.Waiting, power.poll(now = 7 * second, cameraSeen = false, usbReady = false))
        assertEquals(Poll.TimedOut(listOf(Action.PORT_OFF)), power.poll(now = 8 * second, cameraSeen = false, usbReady = false))
        assertEquals(State.OFF, power.state)
    }

    @Test
    fun withoutTheHubThePortIsSkipped() {
        val power = power()
        assertEquals(emptyList(), power.switchOn(hubPresent = false, now = 0))
        assertEquals(State.WAITING_CAMERA, power.state)
        assertEquals(Poll.TimedOut(emptyList()), power.poll(now = 8 * second, cameraSeen = false, usbReady = false))
        power.switchOn(hubPresent = false, now = 10 * second)
        power.poll(now = 10 * second, cameraSeen = true, usbReady = false)
        assertEquals(listOf(Action.CLOSE_CAMERA), power.switchOff())
        assertEquals(State.OFF, power.state)
    }

    @Test
    fun theUsbDeviceAloneWaitsAMomentForCamera2BeforeGoingUvc() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        power.portReady(now = 0)
        assertEquals(Poll.Waiting, power.poll(now = second, cameraSeen = false, usbReady = true))
        assertEquals(Poll.Waiting, power.poll(now = 2 * second, cameraSeen = false, usbReady = true))
        assertEquals(Poll.Open(Path.UVC), power.poll(now = 3 * second, cameraSeen = false, usbReady = true))
        assertEquals(Path.UVC, power.path)
    }

    @Test
    fun ifTheCameraShowsUpDuringTheGraceCamera2Wins() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        power.portReady(now = 0)
        power.poll(now = second, cameraSeen = false, usbReady = true)
        assertEquals(Poll.Open(Path.CAMERA2), power.poll(now = 2 * second, cameraSeen = true, usbReady = true))
    }

    @Test
    fun theUsbDeviceAtTheDeadlineIsOpenedByUvc() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        power.portReady(now = 0)
        assertEquals(Poll.Open(Path.UVC), power.poll(now = 8 * second, cameraSeen = false, usbReady = true))
    }

    @Test
    fun turningOffFromAnyStateEndsOff() {
        for (steps in 0..3) {
            val power = power()
            if (steps >= 1) power.switchOn(hubPresent = true, now = 0)
            if (steps >= 2) power.portReady(now = 0)
            if (steps >= 3) power.poll(now = 0, cameraSeen = true, usbReady = false)
            val actions = power.switchOff()
            if (steps == 0) assertEquals(emptyList(), actions)
            power.poweredOff()
            assertEquals(State.OFF, power.state, "después de $steps pasos")
            assertNull(power.path)
        }
    }

    @Test
    fun closingThePanelWithTheOtoscopeOnClosesTheCameraAndThenThePort() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        power.portReady(now = 0)
        power.poll(now = 0, cameraSeen = false, usbReady = true)
        power.poll(now = 3 * second, cameraSeen = false, usbReady = true)
        assertEquals(listOf(Action.CLOSE_CAMERA, Action.PORT_OFF), power.switchOff())
        assertEquals(State.POWERING_OFF, power.state)
        // Un segundo pedido mientras apaga no repite nada, ni deja encender.
        assertEquals(emptyList(), power.switchOff())
        assertNull(power.switchOn(hubPresent = true, now = 4 * second))
        power.poweredOff()
        assertEquals(State.OFF, power.state)
    }

    @Test
    fun turningOffWhileThePortIsComingUpCancelsTheWait() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        assertEquals(listOf(Action.PORT_OFF), power.switchOff())
        assertFalse(power.portReady(now = second))
        assertEquals(Poll.Cancelled, power.poll(now = second, cameraSeen = true, usbReady = true))
    }

    @Test
    fun itDoesNotTurnOnTwice() {
        val power = power()
        power.switchOn(hubPresent = true, now = 0)
        assertNull(power.switchOn(hubPresent = true, now = 0))
    }
}
