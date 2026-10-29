// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.protocol.EcgInfo
import java.util.Locale

/**
 * Los textos del Panel Local. Están fuera de la pantalla para probarlos sin
 * Android: la plaqueta manda guiones cuando no tiene un valor y el JSON usa
 * punto decimal, pero en la tablet se lee "—" y coma.
 */
object PanelFormat {
    const val NONE = "—"

    /** Un equipo transmite si mandó algo en los últimos 3 s, igual que en Testing. */
    const val LIVE_SECONDS = 3.0

    private val spanish = Locale("es")

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

    fun seconds(age: Double): String = "%.1f".format(spanish, age)

    fun berryLine(connected: Boolean, age: Double?): String = when {
        !connected -> "Plaqueta sin conectar"
        age == null -> "Plaqueta conectada · sin datos todavía"
        age <= LIVE_SECONDS -> "Plaqueta conectada · último dato hace ${seconds(age)} s"
        else -> "Plaqueta conectada · sin datos hace ${age.toLong()} s"
    }

    fun electrodes(info: EcgInfo): String = when (info.leadOff) {
        true -> "Electrodos sueltos"
        false -> "Electrodos conectados"
        null -> "Sin señal de ECG"
    }

    /** El código de arritmia de la plaqueta en palabras; "analizando" no dice nada al médico. */
    fun arrhythmia(hrv: String?): String? {
        if (hrv.isNullOrBlank() || hrv == "analizando") return null
        return hrv.replace('_', ' ')
    }

    fun thermometer(connected: Boolean, reading: String?, ageSeconds: Long?): String = when {
        !connected -> "Termómetro IR: sin conectar"
        reading == null || value(reading) == NONE -> "Termómetro IR: apretá el gatillo para medir"
        ageSeconds == null -> "Termómetro IR: ${value(reading)} °C"
        else -> "Termómetro IR: ${value(reading)} °C · hace ${ageSeconds} s"
    }

    fun nibpDetail(running: Boolean, cuffMmhg: Int, pressure: String): String = when {
        running -> "Midiendo · manguito $cuffMmhg mmHg"
        pressure == NONE -> "Sin medir todavía"
        else -> "Última medición terminada"
    }
}
