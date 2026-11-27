// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.i18n.Txt
import com.guardiavirtual.berrymed.i18n.raw
import com.guardiavirtual.berrymed.i18n.txt
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Los formatos del Panel Local. Están fuera de la pantalla para probarlos sin
 * Android: la plaqueta manda guiones cuando no tiene un valor y el JSON usa
 * punto decimal, pero en la tablet se lee "—" y el separador del idioma.
 *
 * El idioma de las fechas y los números ([locale]) y los patrones de fecha
 * salen del diccionario (`fmt_locale`, `fmt_long_date`, `fmt_short_date_time`):
 * acompañan al idioma de los textos, no al de la tablet. Los arma la pantalla
 * una vez ([panelFormat]). Las horas van en 24 h en todos los idiomas.
 */
class PanelFormat(val locale: Locale, longDatePattern: String, shortDateTimePattern: String) {
    private val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    private val longDate = DateTimeFormatter.ofPattern(longDatePattern, locale)
    private val shortDateTime = DateTimeFormatter.ofPattern(shortDateTimePattern, locale)

    /** "-", "- -", "- -/- -" o vacío pasan a "—"; un número con punto pasa al separador del idioma. */
    fun value(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.all { it == '-' || it == ' ' || it == '/' }) return NONE
        return text.replace('.', decimal)
    }

    /** Un número con decimales con el separador del idioma: 12,5 o 12.5; uno entero, sin decimales. */
    fun number(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString().replace('.', decimal)

    /** Parte "98/72" en saturación y pulso; cada mitad sin dato queda en "—". */
    fun pair(raw: String?): Pair<String, String> {
        val text = raw?.trim().orEmpty()
        val slash = text.indexOf('/')
        if (slash < 0) return value(text) to NONE
        return value(text.substring(0, slash)) to value(text.substring(slash + 1))
    }

    /** La presión entera: "120/80", o "—" si falta alguna de las dos. */
    fun pressure(raw: String?): String {
        val (sys, dia) = pair(raw)
        return if (sys == NONE || dia == NONE) NONE else "$sys/$dia"
    }

    /** La presión media, (sistólica + 2 × diastólica) / 3 redondeada, o null si falta alguna. */
    fun meanPressure(raw: String?): Int? {
        val (sys, dia) = pair(raw)
        val s = sys.toIntOrNull() ?: return null
        val d = dia.toIntOrNull() ?: return null
        return ((s + 2.0 * d) / 3.0).roundToInt()
    }

    /** "15/11/2026 04:58": la última actualización de la configuración. */
    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortDateTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "Miércoles 29 de octubre de 2026": con mayúscula inicial. */
    fun date(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        longDate.format(Instant.ofEpochMilli(millis).atZone(zone)).replaceFirstChar { it.titlecase(locale) }

    companion object {
        const val NONE = "—"

        /** Un equipo transmite si mandó algo en los últimos 3 s, igual que en Testing. */
        const val LIVE_SECONDS = 3.0

        private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

        /**
         * Los códigos de arritmia del protocolo ([com.guardiavirtual.berrymed.protocol.BmDataParser])
         * y su nombre para el médico. El código viaja a la API tal cual; acá sólo se muestra.
         */
        val ARRHYTHMIAS: Map<String, Int> = mapOf(
            "normal" to R.string.arrhythmia_normal,
            "paro" to R.string.arrhythmia_paro,
            "fibrilacion_ventricular" to R.string.arrhythmia_fibrilacion_ventricular,
            "R_sobre_T" to R.string.arrhythmia_r_sobre_t,
            "extrasistoles_ventriculares_multiples" to R.string.arrhythmia_extrasistoles_ventriculares_multiples,
            "extrasistoles_ventriculares_dobles" to R.string.arrhythmia_extrasistoles_ventriculares_dobles,
            "extrasistole_ventricular" to R.string.arrhythmia_extrasistole_ventricular,
            "bigeminismo" to R.string.arrhythmia_bigeminismo,
            "trigeminismo" to R.string.arrhythmia_trigeminismo,
            "taquicardia" to R.string.arrhythmia_taquicardia,
            "bradicardia" to R.string.arrhythmia_bradicardia,
            "latido_perdido" to R.string.arrhythmia_latido_perdido,
        )

        /** El código que no dice nada al médico: la plaqueta todavía está analizando. */
        const val ARRHYTHMIA_ANALYZING = "analizando"

        /**
         * La saturación con su unidad (`local_percent`): «98 %» en español, «98%»
         * en inglés y en portugués; sin dato queda en "—", sin el signo.
         */
        fun percent(value: String): Txt = if (value == NONE) raw(NONE) else txt(R.string.local_percent, value)

        /**
         * El código de arritmia de la plaqueta en palabras; "analizando" no dice
         * nada al médico. Un código que no está en [ARRHYTHMIAS] («desconocido_0x0D») se muestra crudo.
         */
        fun arrhythmia(hrv: String?): Txt? {
            if (hrv.isNullOrBlank() || hrv == ARRHYTHMIA_ANALYZING) return null
            return ARRHYTHMIAS[hrv]?.let { txt(it) } ?: raw(hrv)
        }

        /** "10:07": el reloj de la cabecera y la hora de cada medición. */
        fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            clock.format(Instant.ofEpochMilli(millis).atZone(zone))

        /** "10:12:09": cuándo se congeló el ECG o una imagen. */
        fun clockSeconds(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            clockSeconds.format(Instant.ofEpochMilli(millis).atZone(zone))

        /** "recién" si pasó menos de un minuto, "hace N min" hasta la hora y "hace N h" después. */
        fun ago(nowMillis: Long, atMillis: Long): Txt {
            val minutes = (nowMillis - atMillis).coerceAtLeast(0L) / 60_000L
            return when {
                minutes < 1 -> txt(R.string.local_ago_now)
                minutes < 60 -> txt(R.string.local_ago_minutes, minutes)
                else -> txt(R.string.local_ago_hours, minutes / 60)
            }
        }
    }
}
