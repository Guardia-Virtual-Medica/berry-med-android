// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Los formatos del Panel Local. Están fuera de la pantalla para probarlos sin
 * Android: la plaqueta manda guiones cuando no tiene un valor y el JSON usa
 * punto decimal, pero en la tablet se lee "—" y coma; las horas van en 24 h,
 * como en Argentina.
 */
object PanelFormat {
    const val NONE = "—"

    /** Un equipo transmite si mandó algo en los últimos 3 s, igual que en Testing. */
    const val LIVE_SECONDS = 3.0

    private val argentina = Locale("es", "AR")
    private val clock = DateTimeFormatter.ofPattern("HH:mm", argentina)
    private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss", argentina)
    private val longDate = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", argentina)

    /** "-", "- -", "- -/- -" o vacío pasan a "—"; un número con punto pasa a coma. */
    fun value(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.all { it == '-' || it == ' ' || it == '/' }) return NONE
        return text.replace('.', ',')
    }

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

    /** El código de arritmia de la plaqueta en palabras; "analizando" no dice nada al médico. */
    fun arrhythmia(hrv: String?): String? {
        if (hrv.isNullOrBlank() || hrv == "analizando") return null
        return hrv.replace('_', ' ')
    }

    /** "10:07": el reloj de la cabecera y la hora de cada medición. */
    fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clock.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "10:12:09": cuándo se congeló el ECG o una imagen. */
    fun clockSeconds(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockSeconds.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "Miércoles 29 de octubre de 2026": con mayúscula inicial y sin coma. */
    fun date(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        longDate.format(Instant.ofEpochMilli(millis).atZone(zone)).replaceFirstChar { it.titlecase(argentina) }

    /** "recién" si pasó menos de un minuto, "hace N min" hasta la hora y "hace N h" después. */
    fun ago(nowMillis: Long, atMillis: Long): String {
        val minutes = (nowMillis - atMillis).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1 -> "recién"
            minutes < 60 -> "hace $minutes min"
            else -> "hace ${minutes / 60} h"
        }
    }
}
