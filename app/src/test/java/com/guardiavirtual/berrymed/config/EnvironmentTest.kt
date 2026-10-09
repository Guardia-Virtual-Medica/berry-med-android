// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentTest {
    @Test
    fun eachEnvironmentBringsItsAddressAndPusherKey() {
        assertEquals("https://portal.ondoctor365.com/api/v1/webhooks/totem", Environment.Prod.apiBaseUrl)
        assertEquals("b5b07bcf4eb9935b29c7", Environment.Prod.pusherKey)
        assertEquals("https://portal-dev.ondoctor365.com/api/v1/webhooks/totem", Environment.Dev.apiBaseUrl)
        assertEquals("37b143a0f816ccba0eb2", Environment.Dev.pusherKey)
        assertEquals("sa1", Environment.Prod.pusherCluster)
        assertEquals("sa1", Environment.Dev.pusherCluster)
    }

    @Test
    fun theMetricsUrlIsTheOneOnDoctorListensOn() {
        val config = BerryConfig(totemId = "b81e485b-b438-488f-aa46-6845c8d022a7")
        assertEquals(
            "https://portal.ondoctor365.com/api/v1/webhooks/totem/b81e485b-b438-488f-aa46-6845c8d022a7/metrics",
            config.metricsUrl(),
        )
    }

    @Test
    fun anUnknownKeyFallsBackToProduction() {
        assertEquals(Environment.Prod, Environment.fromKey(null))
        assertEquals(Environment.Prod, Environment.fromKey("staging"))
        assertEquals(Environment.Dev, Environment.fromKey("dev"))
    }

    @Test
    fun aConfigSavedBeforeTheSelectorKeepsItsEnvironment() {
        assertEquals(Environment.Dev, Environment.guess("https://portal-dev.ondoctor365.com/api/v1/webhooks/totem"))
        assertEquals(Environment.Prod, Environment.guess("https://portal.ondoctor365.com/api/v1/webhooks/totem"))
        assertEquals(Environment.Prod, Environment.guess(null))
    }

    @Test
    fun theOldDevKeyIsReplacedByTheRealOne() {
        assertEquals(Environment.Dev, Environment.replacementFor("adac7a549b509b95c930"))
        assertEquals(null, Environment.replacementFor("37b143a0f816ccba0eb2"))
        assertEquals(null, Environment.replacementFor("otra-clave"))
    }

    @Test
    fun theChannelFollowsTheTotemId() {
        assertEquals("totem.b81e485b", defaultChannel(" b81e485b "))
        assertEquals("", defaultChannel(""))
    }

    @Test
    fun onDoctorNeedsNoUserOrPassword() {
        val config = BerryConfig(totemId = "t1", publicChannel = "totem.t1")
        assertTrue(config.postReady())
        assertTrue(config.missingFields().isEmpty())
    }
}
