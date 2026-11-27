// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.i18n.TestStrings
import com.guardiavirtual.berrymed.i18n.Txt
import com.guardiavirtual.berrymed.i18n.es
import com.guardiavirtual.berrymed.protocol.BmDataParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Los nombres de arritmia que ve el médico. El código del protocolo viaja a
 * la API tal cual; lo que se muestra es una entrada del diccionario.
 */
class ArrhythmiaTest {
    /** Los códigos que manda la plaqueta, tal como los arma [BmDataParser] (es privado: se lee por reflexión). */
    @Suppress("UNCHECKED_CAST")
    private val codes: Collection<String> =
        (BmDataParser::class.java.getDeclaredField("HRV_CODES").apply { isAccessible = true }.get(null) as Map<Int, String>).values

    @Test
    fun everyCodeShownHasItsEntry() {
        val shown = codes - PanelFormat.ARRHYTHMIA_ANALYZING
        assertEquals(12, shown.size)
        assertEquals(shown.toSet(), PanelFormat.ARRHYTHMIAS.keys)
        for (code in shown) {
            val text = PanelFormat.arrhythmia(code)
            assertIs<Txt.Res>(text, code)
            // Una entrada arrhythmia_ del diccionario, con texto.
            val name = R.string::class.java.fields.single { it.getInt(null) == text.id }.name
            assertTrue(name.startsWith("arrhythmia_"), "$code → $name")
            assertTrue(TestStrings.catalog().getValue(name).value.isNotBlank(), name)
        }
    }

    @Test
    fun theNamesCarryTheirAccents() {
        val names = PanelFormat.ARRHYTHMIAS.keys.associateWith { es(PanelFormat.arrhythmia(it)!!) }
        assertEquals(
            mapOf(
                "normal" to "normal",
                "paro" to "paro",
                "fibrilacion_ventricular" to "fibrilación ventricular",
                "R_sobre_T" to "R sobre T",
                "extrasistoles_ventriculares_multiples" to "extrasístoles ventriculares múltiples",
                "extrasistoles_ventriculares_dobles" to "extrasístoles ventriculares dobles",
                "extrasistole_ventricular" to "extrasístole ventricular",
                "bigeminismo" to "bigeminismo",
                "trigeminismo" to "trigeminismo",
                "taquicardia" to "taquicardia",
                "bradicardia" to "bradicardia",
                "latido_perdido" to "latido perdido",
            ),
            names,
        )
    }

    @Test
    fun analyzingIsNotShown() {
        assertNull(PanelFormat.arrhythmia(PanelFormat.ARRHYTHMIA_ANALYZING))
        assertNull(PanelFormat.arrhythmia(""))
    }

    @Test
    fun anUnknownCodeIsShownRaw() {
        assertEquals(Txt.Raw("desconocido_0x0D"), PanelFormat.arrhythmia("desconocido_0x0D"))
        assertEquals("desconocido_0x0D", es(PanelFormat.arrhythmia("desconocido_0x0D")!!))
    }
}
