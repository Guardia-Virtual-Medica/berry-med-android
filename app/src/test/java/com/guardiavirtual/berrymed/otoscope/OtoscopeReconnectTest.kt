// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtoscopeReconnectTest {
    @Test
    fun `el mismo otoscopio no se reabre`() {
        assertFalse(UvcOtoscope.Reconnect.shouldReopen(opened = 1005, now = 1005))
    }

    @Test
    fun `reconectado con otro deviceId se reabre`() {
        assertTrue(UvcOtoscope.Reconnect.shouldReopen(opened = 1005, now = 1006))
    }

    @Test
    fun `mientras no esta se espera`() {
        assertFalse(UvcOtoscope.Reconnect.shouldReopen(opened = 1005, now = null))
    }

    @Test
    fun `sin nada abierto no hay que reabrir`() {
        assertFalse(UvcOtoscope.Reconnect.shouldReopen(opened = null, now = 1006))
    }
}
