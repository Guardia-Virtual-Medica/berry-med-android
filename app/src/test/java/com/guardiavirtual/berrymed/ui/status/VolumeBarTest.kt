// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeBarTest {
    @Test
    fun theIndexOfAndroidBecomesAPercent() {
        assertEquals(0, VolumeBar.percent(0, 15))
        assertEquals(47, VolumeBar.percent(7, 15))
        assertEquals(100, VolumeBar.percent(15, 15))
        assertEquals(100, VolumeBar.percent(20, 15))
        assertEquals(0, VolumeBar.percent(3, 0))
    }

    @Test
    fun theIconFollowsTheLevel() {
        assertEquals(VolumeIcon.OFF, VolumeBar.icon(0, 15))
        assertEquals(VolumeIcon.DOWN, VolumeBar.icon(1, 15))
        assertEquals(VolumeIcon.DOWN, VolumeBar.icon(7, 15))
        assertEquals(VolumeIcon.UP, VolumeBar.icon(8, 15))
        assertEquals(VolumeIcon.UP, VolumeBar.icon(15, 15))
        assertEquals(VolumeIcon.OFF, VolumeBar.icon(5, 0))
    }

    @Test
    fun aDraggedPercentGoesToTheNearestIndex() {
        assertEquals(0, VolumeBar.index(0, 15))
        assertEquals(0, VolumeBar.index(3, 15))
        assertEquals(1, VolumeBar.index(4, 15))
        assertEquals(8, VolumeBar.index(50, 15))
        assertEquals(15, VolumeBar.index(100, 15))
        assertEquals(15, VolumeBar.index(130, 15))
        assertEquals(0, VolumeBar.index(-5, 15))
    }

    @Test
    fun goingAndComingBackKeepsTheIndex() {
        for (max in listOf(15, 25)) {
            for (index in 0..max) assertEquals(index, VolumeBar.index(VolumeBar.percent(index, max), max))
        }
    }
}
