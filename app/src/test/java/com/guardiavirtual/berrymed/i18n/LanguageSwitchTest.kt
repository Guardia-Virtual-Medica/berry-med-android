// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.service.StatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A16: el idioma no se cambia con una consulta enviando ni con la teleconsulta en sala. */
class LanguageSwitchTest {
    @Test
    fun blockedDuringAVisit() {
        assertNull(LanguageSwitch.blocked(sessionActive = false, teleInRoom = false))
        val text = "Hay una consulta en curso. Terminala para cambiar el idioma."
        assertEquals(text, LanguageSwitch.blocked(sessionActive = true, teleInRoom = false)?.let(::es))
        assertEquals(text, LanguageSwitch.blocked(sessionActive = false, teleInRoom = true)?.let(::es))
        assertEquals(text, LanguageSwitch.blocked(sessionActive = true, teleInRoom = true)?.let(::es))
        assertEquals("A visit is in progress. End it to change the language.", LanguageSwitch.BLOCKED.inLanguage("en"))
        assertEquals("Há uma consulta em andamento. Encerre-a para mudar o idioma.", LanguageSwitch.BLOCKED.inLanguage("pt"))
    }

    /** La misma regla que el cambio de modo. */
    @Test
    fun theSameRuleAsTheModeSwitch() {
        for (session in listOf(false, true)) {
            for (tele in listOf(false, true)) {
                assertEquals(
                    StatusSnapshot.modeSwitchBlock(session, tele) == null,
                    LanguageSwitch.blocked(session, tele) == null,
                    "consulta=$session, sala=$tele",
                )
            }
        }
    }
}
