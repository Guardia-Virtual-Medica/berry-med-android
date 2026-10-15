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
        watch.started()
        assertFalse(watch.sent(PostOutcome.Http(404)))
        assertFalse(watch.sent(PostOutcome.Http(404)))
        assertTrue(watch.sent(PostOutcome.Http(404)))
    }

    @Test
    fun anAcceptedPacketOrANewSessionResetsTheCount() {
        val watch = SessionWatch()
        watch.sent(PostOutcome.Http(404))
        watch.sent(PostOutcome.Http(404))
        watch.sent(PostOutcome.Ok)
        assertFalse(watch.sent(PostOutcome.Http(404)))
        watch.sent(PostOutcome.Http(404))
        watch.started()
        assertFalse(watch.sent(PostOutcome.Http(404)))
    }

    @Test
    fun otherErrorsDoNotCountAsAClosedSession() {
        val watch = SessionWatch()
        repeat(5) { assertFalse(watch.sent(PostOutcome.Failed("sin red"))) }
        repeat(5) { assertFalse(watch.sent(PostOutcome.Http(500))) }
        repeat(5) { assertFalse(watch.sent(PostOutcome.Http(422))) }
    }
}
