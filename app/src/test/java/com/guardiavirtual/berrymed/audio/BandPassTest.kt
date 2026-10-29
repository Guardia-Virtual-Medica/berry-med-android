// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class BandPassTest {
    private val rate = BandPass.SAMPLE_RATE

    /** Pico de salida en el último segundo de dos, cuando el filtro ya se asentó. */
    private fun peakOut(signal: (Int) -> Double): Double {
        val filter = BandPass()
        var peak = 0.0
        for (i in 0 until rate * 2) {
            val y = filter.process(signal(i))
            if (i >= rate) peak = maxOf(peak, abs(y))
        }
        return peak
    }

    private fun sine(hz: Double): (Int) -> Double = { i -> 1_000.0 * sin(2.0 * PI * hz * i / rate) }

    private fun db(ratio: Double) = 20.0 * log10(ratio)

    @Test
    fun aHundredHertzPassesAlmostUntouched() {
        val gain = db(peakOut(sine(100.0)) / 1_000.0)
        assertTrue(gain > -0.5 && gain < 0.5, "100 Hz quedó en $gain dB")
    }

    @Test
    fun fiveHertzAndTwoKilohertzLoseMoreThanTwentyDecibels() {
        val low = db(peakOut(sine(5.0)) / 1_000.0)
        val high = db(peakOut(sine(2_000.0)) / 1_000.0)
        assertTrue(low < -20.0, "5 Hz quedó en $low dB")
        assertTrue(high < -20.0, "2 kHz quedó en $high dB")
    }

    @Test
    fun aConstantGoesToZero() {
        val peak = peakOut { 1_000.0 }
        assertTrue(peak < 1.0, "la continua quedó en $peak")
    }
}
