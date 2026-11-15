// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import com.guardiavirtual.berrymed.ui.status.NoWifiPrompt.Step
import kotlin.test.Test
import kotlin.test.assertEquals

class NoWifiPromptTest {
    private fun prompt() = NoWifiPrompt(graceMs = 5_000)

    @Test
    fun waitsFiveSecondsWithoutNetwork() {
        val p = prompt()
        assertEquals(Step.NOTHING, p.next(0, connected = false, showing = false, blocked = false))
        assertEquals(Step.NOTHING, p.next(4_999, connected = false, showing = false, blocked = false))
        assertEquals(Step.SHOW, p.next(5_000, connected = false, showing = false, blocked = false))
    }

    @Test
    fun aNetworkThatArrivesInTimeAvoidsIt() {
        val p = prompt()
        p.next(0, connected = false, showing = false, blocked = false)
        p.next(3_000, connected = true, showing = false, blocked = false)
        // Se fue de nuevo: la espera empieza otra vez.
        assertEquals(Step.NOTHING, p.next(4_000, connected = false, showing = false, blocked = false))
        assertEquals(Step.NOTHING, p.next(8_000, connected = false, showing = false, blocked = false))
        assertEquals(Step.SHOW, p.next(9_000, connected = false, showing = false, blocked = false))
    }

    @Test
    fun onceForEachOpening() {
        val p = prompt()
        p.next(0, connected = false, showing = false, blocked = false)
        assertEquals(Step.SHOW, p.next(5_000, connected = false, showing = false, blocked = false))
        // Sigue arriba: no se vuelve a mostrar.
        assertEquals(Step.NOTHING, p.next(5_500, connected = false, showing = true, blocked = false))
        // «Ahora no»: queda cerrado, aunque siga sin red.
        assertEquals(Step.NOTHING, p.next(60_000, connected = false, showing = false, blocked = false))
        // Volver a abrir la pantalla lo habilita de nuevo, con su espera.
        p.restart()
        assertEquals(Step.NOTHING, p.next(61_000, connected = false, showing = false, blocked = false))
        assertEquals(Step.SHOW, p.next(66_000, connected = false, showing = false, blocked = false))
    }

    @Test
    fun closesItselfWhenTheNetworkArrives() {
        val p = prompt()
        p.next(0, connected = false, showing = false, blocked = false)
        p.next(5_000, connected = false, showing = false, blocked = false)
        assertEquals(Step.HIDE, p.next(7_000, connected = true, showing = true, blocked = false))
        assertEquals(Step.NOTHING, p.next(7_500, connected = true, showing = false, blocked = false))
        // Una vez por apertura: si la red se vuelve a ir, no reaparece.
        assertEquals(Step.NOTHING, p.next(20_000, connected = false, showing = false, blocked = false))
    }

    @Test
    fun notAboveThePinOrAnotherDialog() {
        val p = prompt()
        p.next(0, connected = false, showing = false, blocked = true)
        assertEquals(Step.NOTHING, p.next(10_000, connected = false, showing = false, blocked = true))
        // Se cerró lo de arriba: aparece enseguida, la espera ya pasó.
        assertEquals(Step.SHOW, p.next(10_500, connected = false, showing = false, blocked = false))
    }
}
