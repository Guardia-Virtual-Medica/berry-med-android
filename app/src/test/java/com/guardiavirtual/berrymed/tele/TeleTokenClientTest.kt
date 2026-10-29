// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import com.guardiavirtual.berrymed.config.BerryConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeleTokenClientTest {
    private lateinit var server: MockWebServer
    private val client = TeleTokenClient()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun fetch(): TokenOutcome = client.fetch(server.url("/api/v1/webhooks/totem/t-1/livekit-token").toString(), "e-1")

    @Test
    fun theUrlGoesNextToTheMetricsOne() {
        val config = BerryConfig(apiBaseUrl = "https://portal.ondoctor365.com/api/v1/webhooks/totem/", totemId = " t-1 ")
        assertEquals("https://portal.ondoctor365.com/api/v1/webhooks/totem/t-1/livekit-token", config.livekitTokenUrl())
        assertEquals("https://portal.ondoctor365.com/api/v1/webhooks/totem/t-1/metrics", config.metricsUrl())
        assertNull(BerryConfig(apiBaseUrl = "portal.ondoctor365.com", totemId = "t-1").livekitTokenUrl())
        assertNull(BerryConfig(totemId = "").livekitTokenUrl())
    }

    @Test
    fun a200GivesTheServerTheRoomAndTheToken() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"server_url":"wss://lk.example","room":"encounter-e-1","token":"jwt"}"""))
        assertEquals(TokenOutcome.Ok(TeleCredentials("wss://lk.example", "encounter-e-1", "jwt")), fetch())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"encounter_external_id":"e-1"}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
    }

    @Test
    fun a200WithoutTheTokenIsAnError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"server_url":"wss://lk.example","room":"encounter-e-1"}"""))
        assertTrue(fetch() is TokenOutcome.Failed)
    }

    @Test
    fun a409IsNotRetriedAndSaysWhy() {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(TokenOutcome.Failed(TeleTokenClient.describe(409)), fetch())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a409SaysTheCodeFromTheBody() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"x","code":"encounter_invalid_state"}"""))
        val outcome = fetch() as TokenOutcome.Failed
        assertTrue(outcome.message.contains("encounter_invalid_state"), outcome.message)
    }

    @Test
    fun a422AndA429AreNotRetried() {
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(fetch() is TokenOutcome.Failed)
        assertTrue(fetch() is TokenOutcome.Failed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a503IsTriedTwice() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(fetch() is TokenOutcome.Failed)
        assertEquals(2, server.requestCount)
    }
}
