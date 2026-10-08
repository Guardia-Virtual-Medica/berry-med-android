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
    fun aPrivateOrPresenceChannelIsNotConnectedAndTheScreenSaysWhy() {
        for (channel in listOf("private-totem.01", "presence-totem.01")) {
            val config = ready.copy(publicChannel = channel)
            assertFalse(config.pusherReady())
            assertTrue(config.missingFields().any { "canal público" in it })
        }
    }
}
