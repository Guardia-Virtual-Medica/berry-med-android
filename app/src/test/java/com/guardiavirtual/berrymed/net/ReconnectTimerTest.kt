// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectTimerTest {
    private val second = 1_000_000_000L

    @Test
    fun aConnectedClientIsNeverKicked() {
        val timer = ReconnectTimer(afterNanos = 60 * second)
        timer.onState(connected = true, now = 0)
        assertFalse(timer.due(now = 3_600 * second))
    }

    @Test
    fun aClientDownForAMinuteIsKickedOncePerMinute() {
        val timer = ReconnectTimer(afterNanos = 60 * second)
        timer.onState(connected = false, now = 0)
        assertFalse(timer.due(now = 59 * second))
        assertTrue(timer.due(now = 60 * second))
        assertFalse(timer.due(now = 61 * second))
        assertTrue(timer.due(now = 120 * second))
    }

    @Test
    fun repeatedDownStatesDoNotRestartTheCount() {
        val timer = ReconnectTimer(afterNanos = 60 * second)
        timer.onState(connected = false, now = 0)
        timer.onState(connected = false, now = 30 * second)
        assertTrue(timer.due(now = 60 * second))
    }

    @Test
    fun comingBackOrResettingClearsTheCount() {
        val timer = ReconnectTimer(afterNanos = 60 * second)
        timer.onState(connected = false, now = 0)
        timer.onState(connected = true, now = 30 * second)
        assertFalse(timer.due(now = 120 * second))

        timer.onState(connected = false, now = 130 * second)
        timer.reset()
        assertFalse(timer.due(now = 500 * second))
    }
}
