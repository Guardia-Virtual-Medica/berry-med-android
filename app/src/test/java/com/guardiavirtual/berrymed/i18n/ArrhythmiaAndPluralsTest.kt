// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.ui.panel.PanelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArrhythmiaAndPluralsTest {
    private val languages = listOf("") + TestStrings.translations()

    /**
     * A14: los 12 códigos del protocolo tienen su nombre en los tres idiomas,
     * distinto del código; uno desconocido sale crudo. El código que va a la API
     * no cambia: es la clave del mapa, no el texto.
     */
    @Test
    fun everyArrhythmiaHasANameInEveryLanguage() {
        assertEquals(12, PanelFormat.ARRHYTHMIAS.size)
        assertEquals(listOf("en", "pt"), TestStrings.translations())
        for ((code, id) in PanelFormat.ARRHYTHMIAS) {
            for (language in languages) {
                val name = PanelFormat.arrhythmia(code)!!.inLanguage(language)
                assertTrue(name.isNotBlank(), "$code en ${language.ifEmpty { "es" }}")
                // Cada idioma tiene su propia entrada, no la del español.
                if (language.isNotEmpty()) assertTrue(TestStrings.catalog(language).containsKey("arrhythmia_${code.lowercase()}"), "$code en $language")
                assertEquals(txt(id).inLanguage(language), name)
            }
        }
        assertEquals("ventricular fibrillation", PanelFormat.arrhythmia("fibrilacion_ventricular")?.inLanguage("en"))
        assertEquals("fibrilação ventricular", PanelFormat.arrhythmia("fibrilacion_ventricular")?.inLanguage("pt"))
        assertEquals("desconocido_0x0D", PanelFormat.arrhythmia("desconocido_0x0D")?.inLanguage("en"))
    }

    /** A15: cada `<plurals>` tiene `one` y `other` en todos los idiomas (y `many` en español y portugués). */
    @Test
    fun everyPluralHasItsForms() {
        val names = TestStrings.plurals().keys
        assertTrue(names.isNotEmpty())
        for (language in languages) {
            val plurals = TestStrings.plurals(language)
            assertEquals(names, plurals.keys, "plurals de ${language.ifEmpty { "es" }}")
            for ((name, forms) in plurals) {
                val needed = if (language == "en") setOf("one", "other") else setOf("one", "many", "other")
                assertEquals(needed, forms.keys, "$name en ${language.ifEmpty { "es" }}")
            }
        }
        val ok = txt(R.string.rt_post_ok)
        assertEquals("Last send succeeded · 1 packet dropped because the queue was full", plural(R.plurals.rt_post_dropped, 1, ok, 1L).inLanguage("en"))
        assertEquals("Último envio bem-sucedido · 1 pacote descartado por fila cheia", plural(R.plurals.rt_post_dropped, 1, ok, 1L).inLanguage("pt"))
        assertEquals("Último envio bem-sucedido · 3 pacotes descartados por fila cheia", plural(R.plurals.rt_post_dropped, 3, ok, 3L).inLanguage("pt"))
    }
}
