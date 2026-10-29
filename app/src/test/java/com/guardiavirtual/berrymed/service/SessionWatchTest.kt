// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.net.PostOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionWatchTest {
    private val second = 1_000_000_000L

    @Test
    fun theFirstProbeGoesRightAwaySoARestartMidCallRecovers() {
        assertTrue(SessionWatch().probeDue(now = 5 * second))
    }

    @Test
    fun whileWaitingItProbesEveryTenSeconds() {
        val watch = SessionWatch()
        watch.probed(now = 100 * second)
        assertFalse(watch.probeDue(now = 109 * second))
        assertTrue(watch.probeDue(now = 110 * second))
    }

    @Test
    fun afterAStopFromThePlatformItWaitsOutTheGraceMinute() {
        val watch = SessionWatch()
        watch.stopped(now = 100 * second, byPlatform = true)
        assertFalse(watch.probeDue(now = 160 * second))
        assertFalse(watch.probeDue(now = 189 * second))
        assertTrue(watch.probeDue(now = 190 * second))
    }

    @Test
    fun afterAStopOfItsOwnItProbesAgainInTenSeconds() {
        val watch = SessionWatch()
        watch.stopped(now = 100 * second, byPlatform = false)
        assertFalse(watch.probeDue(now = 109 * second))
        assertTrue(watch.probeDue(now = 110 * second))
    }

    @Test
    fun threeNotFoundInARowEndTheSession() {
        val watch = SessionWatch()
        watch.started(epoch = 1, now = 0)
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 10 * second))
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 11 * second))
        assertTrue(watch.sent(PostOutcome.Http(404), epoch = 1, now = 12 * second))
    }

    @Test
    fun anAcceptedPacketOrANewSessionResetsTheCount() {
        val watch = SessionWatch()
        watch.started(epoch = 1, now = 0)
        watch.sent(PostOutcome.Http(404), epoch = 1, now = 10 * second)
        watch.sent(PostOutcome.Http(404), epoch = 1, now = 11 * second)
        watch.sent(PostOutcome.Ok, epoch = 1, now = 12 * second)
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 13 * second))
        watch.sent(PostOutcome.Http(404), epoch = 1, now = 14 * second)
        watch.started(epoch = 3, now = 20 * second)
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 3, now = 30 * second))
    }

    @Test
    fun notFoundFromAPreviousSessionDoNotCount() {
        val watch = SessionWatch()
        watch.started(epoch = 1, now = 0)
        watch.stopped(now = 50 * second, byPlatform = true)
        watch.started(epoch = 3, now = 60 * second)
        // Los POST que seguían en vuelo de la consulta 1 vuelven con 404 ya empezada la 3.
        repeat(5) { assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 70 * second)) }
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 3, now = 71 * second))
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 3, now = 72 * second))
        assertTrue(watch.sent(PostOutcome.Http(404), epoch = 3, now = 73 * second))
    }

    @Test
    fun withoutASessionNoResponseCounts() {
        val watch = SessionWatch()
        watch.started(epoch = 1, now = 0)
        watch.stopped(now = 10 * second, byPlatform = false)
        repeat(5) { assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 20 * second)) }
    }

    @Test
    fun notFoundInTheFirstFiveSecondsDoNotCount() {
        val watch = SessionWatch()
        watch.started(epoch = 1, now = 100 * second)
        // La plataforma todavía no abrió la toma.
        repeat(4) { i -> assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = (101 + i) * second)) }
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 105 * second))
        assertFalse(watch.sent(PostOutcome.Http(404), epoch = 1, now = 106 * second))
        assertTrue(watch.sent(PostOutcome.Http(404), epoch = 1, now = 107 * second))
    }

    @Test
    fun otherErrorsDoNotCountAsAClosedSession() {
        val watch = SessionWatch()
        watch.started(epoch = 1, now = 0)
        val later = 10 * second
        repeat(5) { assertFalse(watch.sent(PostOutcome.Failed("sin red"), epoch = 1, now = later)) }
        repeat(5) { assertFalse(watch.sent(PostOutcome.Http(500), epoch = 1, now = later)) }
        repeat(5) { assertFalse(watch.sent(PostOutcome.Http(422), epoch = 1, now = later)) }
    }
}
