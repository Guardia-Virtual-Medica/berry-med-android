// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtoscopeZoomTest {
    @Test
    fun onTheTabletTheSquareFitsInTheHeightWithTheButtons() {
        // 2560×1600 a 320 dpi: 24 dp de margen son 48 px y la fila de botones, unos 72 dp, 144 px.
        val side = OtoscopeZoom.stageSide(2560, 1600, 48, 144)
        assertEquals(1360, side)
        assertTrue(side + 2 * 48 + 144 <= 1600)
        assertTrue(side + 2 * 48 <= 2560)
    }

    @Test
    fun aWideScreenIsLimitedByItsHeight() {
        assertEquals(1000 - 20 - 100, OtoscopeZoom.stageSide(3000, 1000, 10, 100))
    }

    @Test
    fun aTallScreenIsLimitedByItsWidth() {
        assertEquals(800 - 20, OtoscopeZoom.stageSide(800, 2000, 10, 100))
    }

    @Test
    fun neverNegative() {
        assertEquals(0, OtoscopeZoom.stageSide(10, 10, 24, 72))
        assertEquals(0, OtoscopeZoom.stageSide(0, 0, 0, 0))
    }
}
