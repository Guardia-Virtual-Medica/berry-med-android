// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTargetTest {
    private val url = "https://kiosks-dev.ondoctor365.com/clinicaalem/login"

    @Test
    fun theKioskWhenItIsConfigured() {
        assertEquals(HomeTarget.KIOSK, HomeTarget.of(configLoaded = true, kioskUrl = url))
        assertEquals(HomeTarget.KIOSK, HomeTarget.of(configLoaded = true, kioskUrl = "  $url "))
    }

    @Test
    fun theSettingsWithoutAKioskUrl() {
        assertEquals(HomeTarget.SETTINGS, HomeTarget.of(configLoaded = true, kioskUrl = ""))
        assertEquals(HomeTarget.SETTINGS, HomeTarget.of(configLoaded = true, kioskUrl = "   "))
        assertEquals(HomeTarget.SETTINGS, HomeTarget.of(configLoaded = true, kioskUrl = "no es una url"))
    }

    @Test
    fun waitsForTheConfigurationInsteadOfTheFactoryValues() {
        assertEquals(HomeTarget.WAIT, HomeTarget.of(configLoaded = false, kioskUrl = ""))
        assertEquals(HomeTarget.WAIT, HomeTarget.of(configLoaded = false, kioskUrl = url))
    }
}
