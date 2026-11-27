// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Desde el paso 2 del plan 19 las pantallas no tienen textos escritos a mano:
 * el baseline del lint no puede esconder ninguno. Uno nuevo se arregla
 * pasándolo al diccionario, no agregándolo al baseline.
 */
class LintBaselineTest {
    private val baseline = File("lint-baseline.xml")

    @Test
    fun theBaselineHidesNoHardcodedText() {
        assertTrue("No se encontró ${baseline.absolutePath}", baseline.isFile)
        val text = baseline.readText()
        val hidden = FORBIDDEN.filter { text.contains("id=\"$it\"") }
        assertTrue("lint-baseline.xml esconde $hidden: pasá esos textos a strings.xml", hidden.isEmpty())
    }

    private companion object {
        val FORBIDDEN = listOf("HardcodedText", "SetTextI18n")
    }
}
