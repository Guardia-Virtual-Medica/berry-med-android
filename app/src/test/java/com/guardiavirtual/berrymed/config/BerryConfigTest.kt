// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BerryConfigTest {
    private val ready = BerryConfig(pusherKey = "clave", publicChannel = "totem.01")

    @Test
    fun aPublicChannelIsReady() {
        assertTrue(ready.pusherReady())
        assertTrue(ready.missingFields().none { "público" in it })
    }

    @Test
    fun aUrlWithoutSchemeIsNotPostedAndTheScreenSaysWhy() {
        val config = BerryConfig(apiBaseUrl = "api.ejemplo.com/v1", totemId = "t1", apiUsername = "u", apiPassword = "p")
        assertFalse(config.postReady())
        assertTrue(config.missingFields().any { "https://" in it })
        assertTrue(config.copy(apiBaseUrl = " HTTPS://api.ejemplo.com/v1").postReady())
        assertTrue(config.copy(apiBaseUrl = "http://10.0.0.5/v1").postReady())
    }

    @Test
    fun aPrivateOrPresenceChannelIsNotConnectedAndTheScreenSaysWhy() {
        for (channel in listOf("private-totem.01", "presence-totem.01")) {
            val config = ready.copy(publicChannel = channel)
            assertFalse(config.pusherReady())
            assertTrue(config.missingFields().any { "canal público" in it })
        }
    }

    @Test
    fun theLocalPanelPinIgnoresSpacesAndFallsBackTo1515() {
        assertTrue(pinAccepted("2468", "2468"))
        assertTrue(pinAccepted(" 2468 ", "2468 "))
        assertTrue(pinAccepted("1515", ""))
        assertTrue(pinAccepted("1515", null))
        assertFalse(pinAccepted("1515", "2468"))
        assertFalse(pinAccepted("", "2468"))
        assertFalse(pinAccepted(null, null))
    }
}
