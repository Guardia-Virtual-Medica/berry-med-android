// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGateTest {
    @Test
    fun theLocalPanelDoesNotOpenWithASessionSending() {
        val gate = SessionGate()
        assertTrue(gate.startSession())
        assertFalse(gate.openLocal())
        assertFalse(gate.localMode)
        assertTrue(gate.sessionActive)
    }

    @Test
    fun aSessionDoesNotStartWithTheLocalPanelOpen() {
        val gate = SessionGate()
        assertTrue(gate.openLocal())
        val epoch = gate.epoch
        assertFalse(gate.startSession())
        assertFalse(gate.sessionActive)
        assertEquals(epoch, gate.epoch)
    }

    @Test
    fun anAcceptedProbeDoesNotResumeWithTheLocalPanelOpen() {
        val gate = SessionGate()
        val sentAt = gate.epoch
        gate.openLocal()
        assertFalse(gate.resumeSession(sentAt))
        assertFalse(gate.sessionActive)
    }

    @Test
    fun aProbeSentBeforeTheLastStartOrStopDoesNotResume() {
        val gate = SessionGate()
        val sentAt = gate.epoch
        gate.stopSession()
        assertFalse(gate.resumeSession(sentAt))
        assertFalse(gate.sessionActive)
        assertTrue(gate.resumeSession(gate.epoch))
        assertTrue(gate.sessionActive)
    }

    @Test
    fun resumeDoesNothingWithASessionAlreadySending() {
        val gate = SessionGate()
        gate.startSession()
        val epoch = gate.epoch
        assertFalse(gate.resumeSession(epoch))
        assertEquals(epoch, gate.epoch)
    }

    @Test
    fun startAndStopChangeTheEpoch() {
        val gate = SessionGate()
        val first = gate.epoch
        gate.startSession()
        val started = gate.epoch
        gate.stopSession()
        assertTrue(started != first)
        assertTrue(gate.epoch != started)
        assertFalse(gate.sessionActive)
    }

    @Test
    fun afterClosingThePanelASessionCanStartAndViceVersa() {
        val gate = SessionGate()
        gate.openLocal()
        assertTrue(gate.closeLocal())
        assertFalse(gate.closeLocal())
        assertTrue(gate.startSession())
        gate.stopSession()
        assertTrue(gate.openLocal())
        assertFalse(gate.sessionActive)
    }

    @Test
    fun neverBothOpenWhateverTheOrder() {
        // Todas las secuencias cortas de operaciones: en ninguna quedan abiertos los dos.
        val ops = listOf<(SessionGate) -> Unit>(
            { it.openLocal() },
            { it.closeLocal() },
            { it.startSession() },
            { it.stopSession() },
            { it.resumeSession(it.epoch) },
        )
        fun walk(gate: () -> SessionGate, depth: Int) {
            if (depth == 0) return
            for (op in ops) {
                val next = { gate().also(op) }
                val g = next()
                assertFalse(g.sessionActive && g.localMode)
                walk(next, depth - 1)
            }
        }
        walk({ SessionGate() }, 4)
    }
}
