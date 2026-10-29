// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StethoRecorderTest {
    @Test
    fun fifteenSecondsAtFortyEightKilohertzAreOneAndAHalfMegabytes() {
        val recorder = StethoRecorder()
        val block = ShortArray(StethoscopeLink.BLOCK_SAMPLES) { 7 }
        var blocks = 0
        while (!recorder.isFull) {
            recorder.append(block, block.size)
            blocks++
        }
        // 15 s en bloques de 20 ms.
        assertEquals(750, blocks)
        assertEquals(720_000, recorder.size)
        assertEquals(15.0, recorder.seconds)
        assertEquals(1_440_000, recorder.take().size * 2)
    }

    @Test
    fun itStopsAtTheLimitAndDropsTheRestOfTheBlock() {
        // 10 muestras por segundo y 2 s de tope: entran 20.
        val recorder = StethoRecorder(sampleRate = 10, maxSeconds = 2)
        val block = ShortArray(8) { it.toShort() }
        assertEquals(8, recorder.append(block, 8))
        assertEquals(8, recorder.append(block, 8))
        assertEquals(4, recorder.append(block, 8))
        assertTrue(recorder.isFull)
        assertEquals(0, recorder.append(block, 8))
        assertEquals(2.0, recorder.seconds)
        assertEquals(20, recorder.take().size)
    }

    @Test
    fun itKeepsTheOrderAndOnlyTheCountedSamples() {
        val recorder = StethoRecorder(sampleRate = 10, maxSeconds = 2)
        recorder.append(shortArrayOf(1, 2, 3, 99), 3)
        recorder.append(shortArrayOf(4, 5), 2)
        assertEquals(0.5, recorder.seconds)
        assertContentEquals(shortArrayOf(1, 2, 3, 4, 5), recorder.take())
    }

    @Test
    fun takeLeavesItEmptyForTheNextRecording() {
        val recorder = StethoRecorder(sampleRate = 10, maxSeconds = 1)
        recorder.append(ShortArray(10) { 1 }, 10)
        assertTrue(recorder.isFull)
        recorder.take()
        assertEquals(0, recorder.size)
        assertFalse(recorder.isFull)
        assertEquals(0, recorder.take().size)
        recorder.append(shortArrayOf(9), 1)
        assertContentEquals(shortArrayOf(9), recorder.take())
    }

    @Test
    fun theListShowsTheLastThreeAndKeepsCounting() {
        val list = StethoRecordings()
        for (i in 1..4) list.add(StethoFilter.HEART, i * 1_000L, ShortArray(1))
        assertEquals(listOf(2, 3, 4), list.latest().map { it.number })
        assertEquals(4, list.count)
        assertNull(list.find(1))
        assertEquals(3_000L, list.find(3)?.atMillis)
        list.clear()
        assertTrue(list.latest().isEmpty())
        assertEquals(1, list.add(StethoFilter.LUNG, 0L, ShortArray(1)).number)
    }
}
