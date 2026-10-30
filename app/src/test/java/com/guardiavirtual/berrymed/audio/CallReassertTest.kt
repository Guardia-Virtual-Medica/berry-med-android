// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import com.guardiavirtual.berrymed.audio.CallReassert.Step
import kotlin.test.Test
import kotlin.test.assertEquals

class CallReassertTest {
    private val second = 1_000_000_000L

    @Test
    fun outsideTheCallItDoesNothing() {
        assertEquals(Step.Nothing, CallReassert().next(0, inCall = false, wanted = 1, current = 2))
    }

    @Test
    fun theRightDeviceNeedsNothing() {
        assertEquals(Step.Nothing, CallReassert().next(0, inCall = true, wanted = 1, current = 1))
    }

    @Test
    fun theWrongDeviceIsSetAndNoDeviceToChooseIsLeftAlone() {
        assertEquals(Step.Set, CallReassert().next(0, inCall = true, wanted = 1, current = 2))
        assertEquals(Step.Nothing, CallReassert().next(0, inCall = true, wanted = null, current = 2))
    }

    @Test
    fun itWaitsWhatRemainsOfTheSecond() {
        val guard = CallReassert()
        guard.next(0, inCall = true, wanted = 1, current = 2)
        assertEquals(Step.Wait(second / 4), guard.next(second * 3 / 4, inCall = true, wanted = 1, current = 2))
        assertEquals(Step.Set, guard.next(second, inCall = true, wanted = 1, current = 2))
    }

    @Test
    fun afterTheLimitItGivesUpOnceAndStaysQuiet() {
        val guard = CallReassert()
        val steps = (0..CallReassert.MAX_TRIES + 1).map { n -> guard.next(n * second, true, 1, 2) }
        assertEquals(List(CallReassert.MAX_TRIES) { Step.Set } + Step.GiveUp + Step.Nothing, steps)
    }

    @Test
    fun aDeviceThatComesBackBrieflyDoesNotForgiveTheTries() {
        val guard = CallReassert()
        var now = 0L
        val sets = ArrayList<Step>()
        repeat(CallReassert.MAX_TRIES + 1) {
            sets += guard.next(now, true, 1, 2)
            // Chromium lo pisa enseguida: entre medio se vio el correcto.
            guard.next(now + second / 2, true, 1, 1)
            now += second
        }
        assertEquals(List(CallReassert.MAX_TRIES) { Step.Set } + Step.GiveUp, sets)
    }

    @Test
    fun aDeviceThatHeldForgivesTheTries() {
        val guard = CallReassert()
        var now = 0L
        repeat(CallReassert.MAX_TRIES) {
            guard.next(now, true, 1, 2)
            now += second
        }
        assertEquals(Step.Set, guard.next(now + CallReassert.STABLE_NANOS, true, 1, 2))
    }

    @Test
    fun theNextCallStartsFromZero() {
        val guard = CallReassert()
        (0..CallReassert.MAX_TRIES).forEach { n -> guard.next(n * second, true, 1, 2) }
        guard.next(10 * second, inCall = false, wanted = null, current = null)
        assertEquals(Step.Set, guard.next(11 * second, true, 1, 2))
    }

    @Test
    fun whatBerryMedFixedIsClearedOnceWhenTheCallEnds() {
        val guard = CallReassert()
        guard.next(0, true, 1, 2)
        guard.applied()
        assertEquals(Step.Clear, guard.next(second, inCall = false, wanted = null, current = null))
        assertEquals(Step.Nothing, guard.next(2 * second, inCall = false, wanted = null, current = null))
    }

    @Test
    fun nothingIsClearedIfBerryMedFixedNothing() {
        val guard = CallReassert()
        assertEquals(Step.Nothing, guard.next(0, true, 1, 1))
        assertEquals(Step.Nothing, guard.next(second, false, null, null))
    }

    @Test
    fun aDeviceAndroidRejectedIsNotCleared() {
        val guard = CallReassert()
        assertEquals(Step.Set, guard.next(0, true, 1, 2))
        assertEquals(Step.Nothing, guard.next(second, false, null, null))
    }

    @Test
    fun aNewChoiceDuringTheCallStillClearsAtTheEnd() {
        val guard = CallReassert()
        guard.next(0, true, 1, 2)
        guard.applied()
        guard.reset()
        assertEquals(Step.Clear, guard.next(second, false, null, null))
    }

    @Test
    fun anotherChosenDeviceStartsFromZero() {
        val guard = CallReassert()
        (0..CallReassert.MAX_TRIES).forEach { n -> guard.next(n * second, true, 1, 2) }
        assertEquals(Step.Set, guard.next(10 * second, true, 3, 2))
    }
}
