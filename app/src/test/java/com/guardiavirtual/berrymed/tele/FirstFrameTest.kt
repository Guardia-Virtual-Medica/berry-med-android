// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstFrameTest {
    @Test
    fun withoutFramesItWaitsTheWholeTimeAndSaysNo() {
        val first = FirstFrame()
        val start = System.nanoTime()
        assertFalse(first.await(100))
        assertTrue(System.nanoTime() - start >= 90_000_000L)
    }

    @Test
    fun aFrameThatAlreadyArrivedDoesNotWait() {
        val first = FirstFrame()
        first.arrived()
        assertTrue(first.await(0))
    }

    @Test
    fun aFrameFromAnotherThreadWakesTheWait() {
        val first = FirstFrame()
        thread {
            Thread.sleep(50)
            first.arrived()
        }
        assertTrue(first.await(5_000))
    }

    @Test
    fun moreFramesChangeNothing() {
        val first = FirstFrame()
        repeat(30) { first.arrived() }
        assertTrue(first.await(0))
    }

    @Test
    fun cuttingWinsOverEverything() {
        assertEquals(FirstFrame.Step.CANCELLED, FirstFrame.next(arrived = true, cancelled = true, now = 99, deadline = 10, reconnected = true))
    }

    @Test
    fun anImageIsPublishedEvenAtTheDeadline() {
        assertEquals(FirstFrame.Step.PUBLISH, FirstFrame.next(arrived = true, cancelled = false, now = 10, deadline = 10, reconnected = false))
    }

    @Test
    fun withoutImageItGivesUpAtTheDeadline() {
        assertEquals(FirstFrame.Step.NO_IMAGE, FirstFrame.next(arrived = false, cancelled = false, now = 10, deadline = 10, reconnected = true))
    }

    @Test
    fun aReconnectionBeforeTheDeadlineReopensAndOtherwiseItWaits() {
        assertEquals(FirstFrame.Step.REOPEN, FirstFrame.next(arrived = false, cancelled = false, now = 5, deadline = 10, reconnected = true))
        assertEquals(FirstFrame.Step.WAIT, FirstFrame.next(arrived = false, cancelled = false, now = 5, deadline = 10, reconnected = false))
    }
}
