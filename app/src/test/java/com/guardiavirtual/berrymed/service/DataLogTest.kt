// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataLogTest {
    private val second = 1_000_000_000L

    @Test
    fun theFirstLineAlwaysGoesAndThenOneEveryTenSeconds() {
        val log = DataLog()
        assertTrue(log.due(0, "a"))
        for (s in 1..9) assertFalse(log.due(s * second, "a"))
        assertTrue(log.due(10 * second, "a"))
        assertFalse(log.due(11 * second, "a"))
    }

    @Test
    fun aChangeInTheVitalSignsGoesAtOnce() {
        val log = DataLog()
        assertTrue(log.due(0, "presión 0/0"))
        assertTrue(log.due(second, "presión 108/53"))
        assertFalse(log.due(2 * second, "presión 108/53"))
    }

    @Test
    fun aNewConsultationStartsWithALine() {
        val log = DataLog()
        assertTrue(log.due(0, "a"))
        log.reset()
        assertTrue(log.due(second, "a"))
    }
}
