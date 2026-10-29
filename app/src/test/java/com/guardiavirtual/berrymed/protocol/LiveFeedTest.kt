// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveFeedTest {
    private fun LiveFeed.Channel.pushAll(vararg values: Int) = values.forEach(::push)

    private fun LiveFeed.Channel.read(cursor: Long, size: Int): Pair<LiveFeed.Read, List<Int>> {
        val out = IntArray(size)
        val read = readSince(cursor, out)
        return read to out.take(read.count)
    }

    @Test
    fun pushingPastTheCapacityDropsTheOldest() {
        val channel = LiveFeed.Channel(4)
        channel.pushAll(1, 2, 3, 4, 5, 6)
        assertEquals(6, channel.count())
        val (read, values) = channel.read(0, 10)
        assertEquals(listOf(3, 4, 5, 6), values)
        assertEquals(6, read.cursor)
    }

    @Test
    fun readSinceReturnsOnlyTheNewAndMovesTheCursor() {
        val channel = LiveFeed.Channel(10)
        channel.pushAll(1, 2, 3)
        val (first, firstValues) = channel.read(0, 10)
        assertEquals(listOf(1, 2, 3), firstValues)
        channel.pushAll(4, 5)
        val (second, secondValues) = channel.read(first.cursor, 10)
        assertEquals(listOf(4, 5), secondValues)
        assertEquals(5, second.cursor)
        val (third, thirdValues) = channel.read(second.cursor, 10)
        assertEquals(emptyList(), thirdValues)
        assertEquals(5, third.cursor)
    }

    @Test
    fun aCursorFurtherBackThanTheCapacityStartsFromTheOldestLeft() {
        val channel = LiveFeed.Channel(3)
        channel.pushAll(1, 2, 3, 4, 5, 6, 7)
        val (read, values) = channel.read(1, 10)
        assertEquals(listOf(5, 6, 7), values)
        assertEquals(7, read.cursor)
    }

    @Test
    fun askingForLessThanAvailableKeepsTheNewest() {
        val channel = LiveFeed.Channel(10)
        channel.pushAll(1, 2, 3, 4, 5)
        val (read, values) = channel.read(0, 2)
        assertEquals(listOf(4, 5), values)
        assertEquals(5, read.cursor)
    }

    @Test
    fun clearStartsOverAndAnOldCursorDoesNotBreakTheRead() {
        val channel = LiveFeed.Channel(10)
        channel.pushAll(1, 2, 3)
        val (before, _) = channel.read(0, 10)
        channel.clear()
        assertEquals(0, channel.count())
        assertEquals(0, channel.read(0, 10).first.count)
        channel.pushAll(9)
        // El cursor de antes del clear quedó adelante de lo escrito: se lee lo nuevo igual.
        val (after, values) = channel.read(before.cursor, 10)
        assertEquals(listOf(9), values)
        assertEquals(1, after.cursor)
    }

    @Test
    fun ecgSamplesGoToEachLeadAndNibpKeepsTheLastState() {
        val feed = LiveFeed()
        feed.onEcg(intArrayOf(10, 20, 30, 40, 50, 60, 70))
        val out = IntArray(4)
        assertEquals(1, feed.ecg.getValue("I").readSince(0, out).count)
        assertEquals(10, out[0])
        feed.ecg.getValue("V").readSince(0, out)
        assertEquals(70, out[0])
        feed.onNibp(0x04, 80)
        assertEquals(LiveFeed.NibpState(0x04, 80), feed.nibp)
        feed.clear()
        assertEquals(-1, feed.nibp.status)
        assertEquals(0, feed.ecg.getValue("I").count())
    }
}
