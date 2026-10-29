// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import com.guardiavirtual.berrymed.otoscope.OtoscopeResolution.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtoscopeResolutionTest {
    private val offered = listOf(Size(1440, 1440), Size(480, 480), Size(1280, 1280), Size(960, 960), Size(800, 800))

    @Test
    fun eightHundredSquareWinsWhenOffered() {
        assertEquals(Size(800, 800), OtoscopeResolution.best(offered))
    }

    @Test
    fun withoutItTheClosestSquareWins() {
        assertEquals(Size(960, 960), OtoscopeResolution.best(offered - Size(800, 800)))
        assertEquals(Size(480, 480), OtoscopeResolution.best(listOf(Size(1440, 1440), Size(480, 480))))
    }

    @Test
    fun aSquareBeatsACloserRectangle() {
        assertEquals(Size(960, 960), OtoscopeResolution.best(listOf(Size(800, 600), Size(960, 960))))
        assertEquals(Size(800, 600), OtoscopeResolution.best(listOf(Size(1920, 1080), Size(800, 600))))
    }

    @Test
    fun atTheSameDistanceTheSmallerOneWins() {
        assertEquals(Size(640, 640), OtoscopeResolution.best(listOf(Size(960, 960), Size(640, 640))))
    }

    @Test
    fun theFullOrderAndAnEmptyList() {
        assertEquals(
            listOf(Size(800, 800), Size(960, 960), Size(480, 480), Size(1280, 1280), Size(1440, 1440)),
            OtoscopeResolution.byPreference(offered),
        )
        assertNull(OtoscopeResolution.best(emptyList()))
    }
}
