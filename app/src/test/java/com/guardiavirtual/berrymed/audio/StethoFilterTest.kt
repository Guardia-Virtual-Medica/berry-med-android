// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StethoFilterTest {
    private val rate = BandPass.SAMPLE_RATE

    /** Ganancia en dB de una senoidal de [hz], medida en el último segundo de dos, cuando el filtro ya se asentó. */
    private fun gainDb(filter: StethoFilter, hz: Double): Double {
        val band = filter.bandPass()
        var peak = 0.0
        for (i in 0 until rate * 2) {
            val y = band.process(1_000.0 * sin(2.0 * PI * hz * i / rate))
            if (i >= rate) peak = maxOf(peak, abs(y))
        }
        return 20.0 * log10(peak / 1_000.0)
    }

    @Test
    fun theCenterOfEachBandPassesAlmostUntouched() {
        for (filter in StethoFilter.entries) {
            val center = sqrt(filter.lowHz * filter.highHz)
            val gain = gainDb(filter, center)
            assertTrue(gain > -1.0 && gain < 0.5, "$filter a ${center.toInt()} Hz quedó en $gain dB")
        }
    }

    @Test
    fun aDecadeOutsideEachBandLosesMoreThanTwentyDecibels() {
        for (filter in StethoFilter.entries) {
            val below = gainDb(filter, filter.lowHz / 10.0)
            val above = gainDb(filter, filter.highHz * 10.0)
            assertTrue(below < -20.0, "$filter a ${filter.lowHz / 10} Hz quedó en $below dB")
            assertTrue(above < -20.0, "$filter a ${filter.highHz * 10} Hz quedó en $above dB")
        }
    }

    @Test
    fun theHeartFilterCutsLungSoundsAndTheLungFilterCutsHeartbeats() {
        // 600 Hz es respiratorio; 30 Hz es el golpe del primer ruido cardíaco.
        assertTrue(gainDb(StethoFilter.HEART, 600.0) < gainDb(StethoFilter.LUNG, 600.0) - 10.0)
        assertTrue(gainDb(StethoFilter.LUNG, 30.0) < gainDb(StethoFilter.HEART, 30.0) - 10.0)
    }

    @Test
    fun theBandsAndTheDefault() {
        assertEquals(StethoFilter.HEART, StethoFilter.DEFAULT)
        assertEquals(20.0 to 250.0, StethoFilter.HEART.lowHz to StethoFilter.HEART.highHz)
        assertEquals(100.0 to 1_000.0, StethoFilter.LUNG.lowHz to StethoFilter.LUNG.highHz)
        assertEquals(20.0 to 1_000.0, StethoFilter.WIDE.lowHz to StethoFilter.WIDE.highHz)
    }
}
