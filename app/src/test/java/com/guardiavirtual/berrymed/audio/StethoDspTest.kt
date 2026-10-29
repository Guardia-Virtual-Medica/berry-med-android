// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StethoDspTest {
    @Test
    fun ninetySixSamplesBecomeOneWithTheSignOfTheLargest() {
        val block = ShortArray(StethoDsp.DECIMATION) { 10 }
        block[40] = -900
        block[70] = 500
        val out = IntArray(4)
        assertEquals(1, StethoDsp.decimate(block, block.size, out))
        assertEquals(-900, out[0])
    }

    @Test
    fun aTwentyMillisecondBlockGivesTenPointsAndALeftoverIsDropped() {
        val block = ShortArray(960) { i -> (i / StethoDsp.DECIMATION).toShort() }
        val out = IntArray(20)
        assertEquals(10, StethoDsp.decimate(block, 960, out))
        assertEquals((0..9).toList(), out.take(10))
        assertEquals(1, StethoDsp.decimate(block, StethoDsp.DECIMATION + 50, out))
    }

    @Test
    fun rmsIsMeasuredAgainstFullScale() {
        val square = ShortArray(100) { i -> if (i % 2 == 0) 16_384 else -16_384 }
        assertEquals(16_384 / StethoDsp.FULL_SCALE, StethoDsp.rms(square, 100).toDouble(), 1e-4)
        val half = ShortArray(4) { i -> if (i < 2) 32_767 else 0 }
        assertEquals(sqrt(0.5), StethoDsp.rms(half, 4).toDouble(), 1e-4)
        assertEquals(0f, StethoDsp.rms(square, 0))
    }

    @Test
    fun theLevelBarGoesFromMinusSixtyToZeroDecibels() {
        assertEquals(0, StethoDsp.levelPercent(0f))
        assertEquals(0, StethoDsp.levelPercent(0.0005f))
        assertEquals(100, StethoDsp.levelPercent(1f))
        assertEquals(50, StethoDsp.levelPercent(0.001f * 31.62f))
    }

    @Test
    fun theSoftClipNeverReachesFullScale() {
        assertEquals(2_000.0, StethoDsp.amplify(1_000.0, 2.0))
        assertEquals(1_200.0, StethoDsp.amplify(1_000.0, 1.2), 1e-9)
        val loud = StethoDsp.amplify(30_000.0, 2.0)
        assertTrue(loud > 16_384 && loud < StethoDsp.FULL_SCALE, "quedó en $loud")
        assertEquals(-loud, StethoDsp.amplify(-30_000.0, 2.0))
        assertEquals(Short.MAX_VALUE, StethoDsp.toPcm(40_000.0))
    }

    @Test
    fun theVolumeIsTheGainOverFive() {
        assertEquals(0.0, StethoDsp.volumeGain(0))
        assertEquals(1.0, StethoDsp.volumeGain(5))
        assertEquals(1.2, StethoDsp.volumeGain(StethoDsp.DEFAULT_VOLUME), 1e-9)
        assertEquals(2.0, StethoDsp.volumeGain(10))
        // Fuera de la escala queda en el extremo.
        assertEquals(2.0, StethoDsp.volumeGain(14))
        assertEquals(0.0, StethoDsp.volumeGain(-3))
        assertEquals(0.0, StethoDsp.amplify(12_000.0, StethoDsp.volumeGain(0)))
    }
}
