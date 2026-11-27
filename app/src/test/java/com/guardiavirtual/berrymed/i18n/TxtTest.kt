// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.R
import java.util.Locale
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
    fun aPluralPicksItsFormByTheQuantity() {
        val sent = txt(R.string.rt_post_ok)
        assertEquals("Último envío correcto · 1 paquete descartado por cola llena", es(plural(R.plurals.rt_post_dropped, 1, sent, 1L)))
        assertEquals("Último envío correcto · 3 paquetes descartados por cola llena", es(plural(R.plurals.rt_post_dropped, 3, sent, 3L)))
        // «many» (los millones exactos en español) dice lo mismo que «other».
        assertEquals(
            "Último envío correcto · 1000000 paquetes descartados por cola llena",
            es(plural(R.plurals.rt_post_dropped, 1_000_000, sent, 1_000_000L)),
        )
        assertEquals(plural(R.plurals.rt_post_dropped, 2, sent, 2L), plural(R.plurals.rt_post_dropped, 2, sent, 2L))
        assertNotEquals(plural(R.plurals.rt_post_dropped, 2, sent, 2L), plural(R.plurals.rt_post_dropped, 1, sent, 1L))
    }

    @Test
    fun aJoinPutsWholeSentencesSideBySide() {
        val line = join(listOf(txt(R.string.rt_thermo_disconnected), txt(R.string.rt_hub_absent)), " ")
        assertEquals("Termómetro sin conectar. Hub YKUSH3 sin conectar.", es(line))
        // Adentro de otro texto, y con lo que no se traduce.
        val missing = txt(R.string.rt_missing_config, join(listOf(txt(R.string.rt_missing_totem), raw("canal")), ", "))
        assertEquals("Falta configurar: tótem, canal.", es(missing))
        assertEquals("", es(join(emptyList(), ", ")))
        assertEquals(line, join(listOf(txt(R.string.rt_thermo_disconnected), txt(R.string.rt_hub_absent)), " "))
    }

    /** El decimal sale con el idioma de fmt_locale del diccionario, no con el de la tablet ni el de la JVM. */
    @Test
    fun aDecimalIsWrittenInTheLanguageOfTheDictionary() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("Último dato: hace 1,5 s", es(txt(R.string.rt_last_data_ago, 1.5)))
            assertEquals("Último dato: hace 12,0 s", es(txt(R.string.rt_last_data_ago, 12.0)))
            // Un idioma sin traducción cae al español, con su fmt_locale.
            assertEquals("Último dato: hace 0,3 s", txt(R.string.rt_last_data_ago, 0.25).inLanguage("en"))
            // Los enteros no cambian: los textos de los pasos anteriores resuelven igual.
            assertEquals("En vivo · UVC · 29 cuadros/s", es(txt(R.string.local_status_otoscope_live, txt(R.string.local_otoscope_path_uvc), 29)))
        } finally {
            Locale.setDefault(before)
        }
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
