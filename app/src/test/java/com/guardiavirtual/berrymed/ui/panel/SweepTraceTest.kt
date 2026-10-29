// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.protocol.LiveFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SweepTraceTest {
    private fun channelWith(vararg values: Int) = LiveFeed.Channel(100).apply { values.forEach(::push) }

    @Test
    fun feedWritesLeftToRightAndWrapsAround() {
        val trace = SweepTrace(4)
        val channel = channelWith(1, 2, 3)
        assertEquals(3, trace.feed(channel))
        assertEquals(3, trace.head)
        channel.push(4)
        channel.push(5)
        assertEquals(2, trace.feed(channel))
        assertEquals(1, trace.head)
        assertEquals(listOf(5f, 2f, 3f, 4f), trace.values.toList())
        // Sin nada nuevo no escribe.
        assertEquals(0, trace.feed(channel))
    }

    @Test
    fun theGapCoversThePositionsAheadOfTheCursorWithWrap() {
        val trace = SweepTrace(5)
        trace.feed(channelWith(1, 2, 3, 4))
        assertEquals(4, trace.head)
        assertTrue(trace.inGap(4, 2))
        assertTrue(trace.inGap(0, 2))
        assertFalse(trace.inGap(1, 2))
        assertFalse(trace.inGap(3, 2))
    }

    @Test
    fun segmentsSkipMissingSamplesAndTheGap() {
        val trace = SweepTrace(6)
        trace.feed(channelWith(1, 2, 3))
        val out = FloatArray(20)
        // Llegaron 0..2; el hueco son las posiciones 3 y 4; la 5 no tiene dato.
        val n = trace.segments(out, xOf = { it.toFloat() }, yOf = { it }, gap = 2)
        assertEquals(8, n)
        assertEquals(listOf(0f, 1f, 1f, 2f, 1f, 2f, 2f, 3f), out.take(n))
        // Con el hueco más grande, la muestra 2 queda adentro y no se une.
        assertEquals(0, SweepTrace(6).also { it.feed(channelWith(1, 2)) }.segments(out, { it.toFloat() }, { it }, gap = 5))
    }

    @Test
    fun rangeWithAndWithoutData() {
        val trace = SweepTrace(10)
        assertNull(trace.range())
        trace.feed(channelWith(40, 10, 25))
        assertEquals(10f to 40f, trace.range())
        trace.reset()
        assertNull(trace.range())
    }

    @Test
    fun samplesPerScreenFollowsThePaperSpeed() {
        assertEquals(1000, SweepTrace.samplesPerScreen(600f, 6f, 25f, 250f))
        assertEquals(2000, SweepTrace.samplesPerScreen(600f, 6f, 12.5f, 250f))
    }
}
