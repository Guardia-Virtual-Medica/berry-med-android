// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtoscopeReconnectTest {
    @Test
    fun `el mismo otoscopio no se reabre`() {
        assertFalse(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005))
    }

    @Test
    fun `reconectado con otro deviceId se reabre`() {
        assertTrue(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1006))
    }

    @Test
    fun `mientras no esta se espera`() {
        assertFalse(OtoscopeReconnect.shouldReopen(opened = 1005, now = null))
    }

    @Test
    fun `sin nada abierto no hay que reabrir`() {
        assertFalse(OtoscopeReconnect.shouldReopen(opened = null, now = 1006))
    }

    @Test
    fun `el mismo otoscopio mandando imagen no se reabre`() {
        assertFalse(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005, quietMs = 4_999, stalls = 0))
    }

    @Test
    fun `el mismo otoscopio sin imagen hace 5 s se reabre`() {
        assertTrue(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005, quietMs = 5_000, stalls = 0))
    }

    @Test
    fun `cada reapertura sin imagen espera mas`() {
        assertFalse(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005, quietMs = 14_999, stalls = 1))
        assertTrue(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005, quietMs = 15_000, stalls = 1))
        assertFalse(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005, quietMs = 29_999, stalls = 2))
        assertTrue(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1005, quietMs = 30_000, stalls = 9))
    }

    @Test
    fun `reconectado se reabre aunque recien haya llegado imagen`() {
        assertTrue(OtoscopeReconnect.shouldReopen(opened = 1005, now = 1006, quietMs = 0, stalls = 5))
    }

    @Test
    fun `sin imagen pero desenchufado se espera`() {
        assertFalse(OtoscopeReconnect.shouldReopen(opened = 1005, now = null, quietMs = 60_000, stalls = 0))
    }

    @Test
    fun `el motivo distingue la reconexion de la falta de imagen`() {
        assertTrue(OtoscopeReconnect.why(opened = 1005, now = 1006).startsWith("se reconectó"))
        assertTrue(OtoscopeReconnect.why(opened = 1005, now = 1005).contains("dejó de mandar imagen"))
    }
}
