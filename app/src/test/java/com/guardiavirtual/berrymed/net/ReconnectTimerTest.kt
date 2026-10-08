// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectTimerTest {
    private val second = 1_000_000_000L

    @Test
    fun aClientThatIsStillTryingIsLeftAlone() {
        val timer = ReconnectTimer(afterNanos = 30 * second)
        timer.onState(gaveUp = false, now = 0)
        assertFalse(timer.due(now = 3_600 * second))
    }

    @Test
    fun aClientThatGaveUpIsKickedOncePerInterval() {
        val timer = ReconnectTimer(afterNanos = 30 * second)
        timer.onState(gaveUp = true, now = 0)
        assertFalse(timer.due(now = 29 * second))
        assertTrue(timer.due(now = 30 * second))
        assertFalse(timer.due(now = 31 * second))
        assertTrue(timer.due(now = 60 * second))
    }

    @Test
    fun repeatedGiveUpsDoNotRestartTheCount() {
        val timer = ReconnectTimer(afterNanos = 30 * second)
        timer.onState(gaveUp = true, now = 0)
        timer.onState(gaveUp = true, now = 20 * second)
        assertTrue(timer.due(now = 30 * second))
    }

    @Test
    fun tryingAgainOrResettingClearsTheCount() {
        val timer = ReconnectTimer(afterNanos = 30 * second)
        timer.onState(gaveUp = true, now = 0)
        timer.onState(gaveUp = false, now = 10 * second)
        assertFalse(timer.due(now = 120 * second))

        timer.onState(gaveUp = true, now = 130 * second)
        timer.reset()
        assertFalse(timer.due(now = 500 * second))
    }
}
