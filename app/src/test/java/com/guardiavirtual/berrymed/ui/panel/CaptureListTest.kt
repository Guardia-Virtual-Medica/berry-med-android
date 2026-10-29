// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureListTest {
    /** Una imagen de mentira: se distingue por el nombre y por la identidad. */
    private class Image(val name: String) {
        override fun toString() = name
    }

    private val released = mutableListOf<String>()
    private val list = CaptureList<Image>(release = { released += it.name })

    private fun add(n: Int) = list.add(atMillis = n * 1_000L, thumb = Image("mini$n"), full = Image("full$n"))

    @Test
    fun keepsTheLastFourInOrder() {
        (1..6).forEach { add(it) }
        val shown = list.latest()
        assertEquals(listOf(3, 4, 5, 6), shown.map { it.number })
        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L), shown.map { it.atMillis })
        assertEquals(listOf("mini3", "mini4", "mini5", "mini6"), shown.map { it.thumb.name })
        assertEquals(listOf(5, 6), list.latest(2).map { it.number })
    }

    @Test
    fun theCountKeepsGrowingPastWhatIsShown() {
        (1..7).forEach { add(it) }
        assertEquals(7, list.count)
        assertEquals(4, list.latest().size)
    }

    @Test
    fun onlyTheLastKeepsTheFullImage() {
        (1..3).forEach { add(it) }
        val shown = list.latest()
        assertNull(shown[0].full)
        assertNull(shown[1].full)
        assertEquals("full3", shown[2].full?.name)
        // Las completas anteriores se soltaron; las miniaturas siguen.
        assertEquals(listOf("full1", "full2"), released)
    }

    @Test
    fun theOnesThatStopShowingAreReleased() {
        (1..5).forEach { add(it) }
        assertTrue("mini1" in released)
        assertTrue("mini2" !in released)
    }

    @Test
    fun clearEmptiesAndReleasesEverything() {
        (1..3).forEach { add(it) }
        released.clear()
        list.clear()
        assertEquals(0, list.count)
        assertTrue(list.latest().isEmpty())
        assertEquals(setOf("mini1", "mini2", "mini3", "full3"), released.toSet())
        assertEquals(4, released.size)
        add(9)
        assertEquals(1, list.latest().single().number)
    }

    @Test
    fun aSmallImageIsItsOwnThumbnailAndIsReleasedOnce() {
        val small = Image("chica")
        list.add(1_000L, small, small)
        add(2)
        assertSame(small, list.latest().first().thumb)
        assertTrue(released.isEmpty())
        list.clear()
        assertEquals(1, released.count { it == "chica" })
    }

    @Test
    fun thumbnailsKeepTheProportionWithTheLongestSideAt320() {
        assertEquals(320 to 180, CaptureList.thumbSize(1600, 900))
        assertEquals(320 to 320, CaptureList.thumbSize(800, 800))
        assertEquals(180 to 320, CaptureList.thumbSize(900, 1600))
        assertEquals(200 to 100, CaptureList.thumbSize(200, 100))
    }
}
