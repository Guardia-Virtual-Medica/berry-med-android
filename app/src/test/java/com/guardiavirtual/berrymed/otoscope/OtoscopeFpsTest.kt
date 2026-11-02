// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtoscopeFpsTest {
    @Test
    fun uvcAsksForExactlyThirtyFirstAndThenTheRange() {
        assertEquals(listOf(30..30, 1..30), OtoscopeFps.uvcAttempts())
    }

    @Test
    fun camera2TakesFixedThirtyWhenOffered() {
        assertEquals(30 to 30, OtoscopeFps.camera2Range(listOf(15 to 30, 30 to 30, 5 to 5)))
    }

    @Test
    fun camera2OtherwiseTakesTheHighestFloorThatReachesThirty() {
        assertEquals(15 to 30, OtoscopeFps.camera2Range(listOf(15 to 30, 5 to 5)))
        assertEquals(15 to 30, OtoscopeFps.camera2Range(listOf(7 to 30, 15 to 30, 15 to 60)))
    }

    @Test
    fun camera2WithoutThirtyAsksForNothing() {
        assertNull(OtoscopeFps.camera2Range(listOf(5 to 5)))
        assertNull(OtoscopeFps.camera2Range(listOf(5 to 15, 60 to 60)))
        assertNull(OtoscopeFps.camera2Range(emptyList()))
    }

    @Test
    fun theMeterSaysTheRateOncePerSecond() {
        val meter = OtoscopeFps.Meter()
        val rates = mutableListOf<Double>()
        // Tres segundos: el primero a 30 cuadros, el segundo a 5 y el tercero otra vez a 30.
        val frames = (0..30).map { it * SECOND / 30 } +
            (1..5).map { SECOND + it * SECOND / 5 } +
            (1..30).map { 2 * SECOND + it * SECOND / 30 }
        for (nanos in frames) meter.frame(START + nanos)?.let { rates += it }
        assertEquals(listOf(30.0, 5.0, 30.0), rates)
    }

    @Test
    fun theLowWatchTellsOnceAfterFiveSecondsBelowTwenty() {
        val watch = OtoscopeFps.LowWatch()
        val told = (0..8).map { second -> watch.rate(12.0, START + second * SECOND) }
        assertEquals(listOf(false, false, false, false, false, true, false, false, false), told)
    }

    @Test
    fun theLowWatchStartsOverWhenTheRateRecovers() {
        val watch = OtoscopeFps.LowWatch()
        for (second in 0..3) assertFalse(watch.rate(12.0, START + second * SECOND))
        assertFalse(watch.rate(29.0, START + 4 * SECOND))
        for (second in 5..9) assertFalse(watch.rate(12.0, START + second * SECOND))
        assertTrue(watch.rate(12.0, START + 10 * SECOND))
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val START = 7_000_000_000L
    }
}
