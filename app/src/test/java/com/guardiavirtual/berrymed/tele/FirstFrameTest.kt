// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import kotlin.concurrent.thread
import kotlin.test.Test
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
}
