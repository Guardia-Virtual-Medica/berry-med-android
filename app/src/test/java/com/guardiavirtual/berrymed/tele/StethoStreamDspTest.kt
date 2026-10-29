// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StethoStreamDspTest {
    /** Bloques de 10 ms a 48 kHz, como los entrega WebRTC. */
    private fun block(samples: (Int) -> Int, offset: Int, count: Int = 480): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) buffer.putShort(i * 2, samples(offset + i).toShort())
        return buffer
    }

    private fun peak(buffer: ByteBuffer, count: Int = 480): Int =
        (0 until count).maxOf { abs(buffer.order(ByteOrder.nativeOrder()).getShort(it * 2).toInt()) }

    @Test
    fun aConstantOffsetIsFilteredOut() {
        val dsp = StethoStreamDsp()
        var last = 0
        for (n in 0 until 100) {
            val buffer = block({ 5_000 }, n * 480)
            dsp.process(buffer, 960, 48_000, 1)
            last = peak(buffer)
        }
        assertTrue(last < 50, "quedó $last")
    }

    @Test
    fun aHeartSoundPassesWithTheDefaultGain() {
        val dsp = StethoStreamDsp()
        val tone = { i: Int -> (8_000 * sin(2 * PI * 100.0 * i / 48_000)).toInt() }
        var last = 0
        for (n in 0 until 50) {
            val buffer = block(tone, n * 480)
            dsp.process(buffer, 960, 48_000, 1)
            last = peak(buffer)
        }
        // Volumen 6 de 10: x1,2, con el filtro casi plano a 100 Hz.
        assertTrue(last in 8_500..10_500, "pico $last")
        assertTrue(dsp.level > 0.1f)
        assertEquals(50L, dsp.blocks)
    }

    @Test
    fun withTwoChannelsItOnlyMeasures() {
        val dsp = StethoStreamDsp()
        val buffer = block({ 5_000 }, 0)
        dsp.process(buffer, 960, 48_000, 2)
        assertEquals(5_000, peak(buffer))
        assertTrue(dsp.level > 0.1f)
    }

    @Test
    fun resetForgetsTheLevel() {
        val dsp = StethoStreamDsp()
        dsp.process(block({ 5_000 }, 0), 960, 48_000, 1)
        dsp.reset()
        assertEquals(0f, dsp.level)
    }
}
