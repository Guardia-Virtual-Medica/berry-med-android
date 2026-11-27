// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** El texto sin idioma: las reglas comparan claves y la pantalla lo resuelve. */
class TxtTest {
    @Test
    fun theSameKeyAndParametersAreTheSameText() {
        assertEquals(txt(R.string.status_connected), txt(R.string.status_connected))
        assertNotEquals(txt(R.string.status_connected), txt(R.string.status_connecting))
        assertEquals(txt(R.string.local_ago_minutes, 5L), txt(R.string.local_ago_minutes, 5L))
        assertNotEquals(txt(R.string.local_ago_minutes, 5L), txt(R.string.local_ago_minutes, 6L))
        // Una clave con el mismo texto en otro lugar es otra entrada: cada idioma puede elegir otra palabra.
        assertEquals(es(txt(R.string.status_leads_off)), es(txt(R.string.local_leads_off)))
        assertNotEquals(txt(R.string.status_leads_off), txt(R.string.local_leads_off))
    }

    @Test
    fun parametersAreNumberedAndCanBeTexts() {
        assertEquals("hace 5 min", es(txt(R.string.local_ago_minutes, 5L)))
        // Un Txt adentro de otro se resuelve en el mismo idioma.
        val at = txt(R.string.local_pa_at, "10:07", txt(R.string.local_ago_minutes, 5L))
        assertEquals("10:07 · hace 5 min", es(at))
        assertEquals(at, txt(R.string.local_pa_at, "10:07", txt(R.string.local_ago_minutes, 5L)))
        val live = txt(R.string.local_status_otoscope_live, txt(R.string.local_otoscope_path_uvc), 29)
        assertEquals("En vivo · UVC · 29 cuadros/s", es(live))
    }

    @Test
    fun rawIsNotTranslated() {
        assertEquals("Consultorio-5G", es(raw("Consultorio-5G")))
        assertEquals(raw("Consultorio-5G"), raw("Consultorio-5G"))
        assertNotEquals<Txt>(raw("Conectado"), txt(R.string.status_connected))
        // Como parámetro también queda igual.
        assertEquals("OK, SpO₂/pulso 98/72", es(txt(R.string.testing_verdict_spo2_ok, raw("98/72"))))
    }
}
