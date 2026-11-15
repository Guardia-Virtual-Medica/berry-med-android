// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class UiScaleTest {
    @Test
    fun theTabletGoesTo800DpTall() {
        // 1920×1080 a 160 dpi: 216 dpi, 1080 px son 800 dp.
        assertEquals(216, UiScale.targetDpi(shortPx = 1080, currentDpi = 160))
    }

    @Test
    fun aScreenAlready800DpTallStaysTheSame() {
        // 1280×800 a 160 y 1920×1200 a 240 ya miden 800 dp.
        assertEquals(160, UiScale.targetDpi(shortPx = 800, currentDpi = 160))
        assertEquals(240, UiScale.targetDpi(shortPx = 1200, currentDpi = 240))
    }

    @Test
    fun neverShrinks() {
        // Una pantalla más chica que el diseño no se achica más.
        assertEquals(240, UiScale.targetDpi(shortPx = 720, currentDpi = 240))
    }
}
