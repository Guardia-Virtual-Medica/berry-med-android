// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsPosterTest {
    private lateinit var server: MockWebServer
    private val poster = MetricsPoster()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun post(): PostOutcome = poster.post(server.url("/totem/metrics").toString(), "", "", "{}")

    @Test
    fun a200IsOk() {
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(PostOutcome.Ok, post())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a201IsOkAndIsNotSentAgain() {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        assertEquals(PostOutcome.Ok, post())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a404IsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(PostOutcome.Http(404), post())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a503IsTriedTwiceWithAPauseInBetween() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        val startedAt = System.nanoTime()
        assertEquals(PostOutcome.Http(503), post())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertEquals(2, server.requestCount)
        assertTrue(elapsedMs >= MetricsPoster.RETRY_PAUSE_MS, "esperó $elapsedMs ms")
    }

    @Test
    fun aServerThatNeverAnswersGivesUpAfterTwoBoundedAttempts() {
        // Con tiempos cortos para que el test no tarde: dos intentos de 300 ms más la pausa.
        val quick = MetricsPoster(callTimeoutMs = 300, retryPauseMs = 100)
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val startedAt = System.nanoTime()
        val outcome = quick.post(server.url("/totem/metrics").toString(), "", "", "{}")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(outcome is PostOutcome.Failed)
        assertEquals(2, server.requestCount)
        assertTrue(elapsedMs < 2_000, "tardó $elapsedMs ms")
    }
}
