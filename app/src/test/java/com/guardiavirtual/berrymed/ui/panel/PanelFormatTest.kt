// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.i18n.es
import com.guardiavirtual.berrymed.i18n.panelFormatIn
import com.guardiavirtual.berrymed.protocol.NO_PAIR
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PanelFormatTest {
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")

    /** Los formatos del español, leídos del diccionario como los arma la pantalla. */
    private val format = panelFormatIn()

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(2026, 10, 29, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theBoardDashesShowAsALongDash() {
        for (raw in listOf("-", "- -", NO_PAIR, "- -/- -", "", null)) assertEquals("—", format.value(raw))
        assertEquals("72", format.value("72"))
        assertEquals("36,6", format.value("36.6"))
    }

    @Test
    fun spo2AndPulseAreSplit() {
        assertEquals("98" to "72", format.pair("98/72"))
        assertEquals("—" to "72", format.pair("-/72"))
        assertEquals("—" to "—", format.pair(NO_PAIR))
    }

    @Test
    fun pressureNeedsBothValues() {
        assertEquals("120/80", format.pressure("120/80"))
        assertEquals("—", format.pressure(NO_PAIR))
        assertEquals("—", format.pressure("120/-"))
    }

    @Test
    fun meanPressureIsOneThirdSystolicTwoThirdsDiastolic() {
        assertEquals(93, format.meanPressure("120/80"))
        // (116 + 158) / 3 = 91,33: se redondea.
        assertEquals(91, format.meanPressure("116/79"))
        assertEquals(97, format.meanPressure("125/83"))
        assertNull(format.meanPressure(NO_PAIR))
    }

    @Test
    fun arrhythmia() {
        assertNull(PanelFormat.arrhythmia("analizando"))
        assertNull(PanelFormat.arrhythmia(null))
        assertEquals("fibrilación ventricular", PanelFormat.arrhythmia("fibrilacion_ventricular")?.let(::es))
    }

    @Test
    fun clockAndDateInArgentineSpanish() {
        assertEquals("10:07", PanelFormat.clock(at(10, 7), zone))
        assertEquals("09:05", PanelFormat.clock(at(9, 5), zone))
        assertEquals("10:12:09", PanelFormat.clockSeconds(at(10, 12, 9), zone))
        assertEquals("29/10/2026 09:05", format.dateTime(at(9, 5), zone))
        assertEquals("Jueves 29 de octubre de 2026", format.date(at(10, 12), zone))
    }

    @Test
    fun agoSaysRecienUnderAMinute() {
        val now = at(10, 12)
        assertEquals("recién", es(PanelFormat.ago(now, now - 59_000)))
        assertEquals("hace 1 min", es(PanelFormat.ago(now, now - 60_000)))
        assertEquals("hace 4 min", es(PanelFormat.ago(now, at(10, 7, 30))))
        assertEquals("hace 2 h", es(PanelFormat.ago(now, at(8, 0))))
        // Un reloj que vuelve atrás no da minutos negativos.
        assertEquals("recién", es(PanelFormat.ago(now, now + 5_000)))
    }

    @Test
    fun percentAddsTheSignOnlyWithAValue() {
        assertEquals("98 %", PanelFormat.percent("98"))
        assertEquals("—", PanelFormat.percent(PanelFormat.NONE))
    }

    /**
     * Fechas y números en otros idiomas, con el idioma y los patrones pasados a
     * mano (todavía no hay traducciones): el decimal sale del idioma y la hora
     * queda en 24 h en todos.
     */
    @Test
    fun theSameInstantAndNumbersInEachLanguage() {
        val spanish = PanelFormat(Locale.forLanguageTag("es-AR"), "EEEE d 'de' MMMM 'de' yyyy", "dd/MM/yyyy HH:mm")
        val english = PanelFormat(Locale.forLanguageTag("en-US"), "EEEE, MMMM d, yyyy", "MM/dd/yyyy HH:mm")
        val portuguese = PanelFormat(Locale.forLanguageTag("pt-BR"), "EEEE, d 'de' MMMM 'de' yyyy", "dd/MM/yyyy HH:mm")

        assertEquals("12,5", spanish.number(12.5f))
        assertEquals("12.5", english.number(12.5f))
        assertEquals("12,5", portuguese.number(12.5f))
        assertEquals("25", english.number(25f))
        assertEquals("36,6", spanish.value("36.6"))
        assertEquals("36.6", english.value("36.6"))
        assertEquals("36,6", portuguese.value("36.6"))

        assertEquals("Jueves 29 de octubre de 2026", spanish.date(at(10, 12), zone))
        assertEquals("Thursday, October 29, 2026", english.date(at(10, 12), zone))
        assertEquals("Quinta-feira, 29 de outubro de 2026", portuguese.date(at(10, 12), zone))

        // La hora, en 24 h en los tres.
        assertEquals("29/10/2026 21:05", spanish.dateTime(at(21, 5), zone))
        assertEquals("10/29/2026 21:05", english.dateTime(at(21, 5), zone))
        assertEquals("29/10/2026 21:05", portuguese.dateTime(at(21, 5), zone))
        assertEquals("21:05", PanelFormat.clock(at(21, 5), zone))
    }

    /** El español del diccionario es el mismo que el de arriba, escrito a mano. */
    @Test
    fun theDictionaryGivesArgentineSpanish() {
        assertEquals(Locale.forLanguageTag("es-AR"), format.locale)
        assertEquals("12,5", format.number(12.5f))
    }
}
