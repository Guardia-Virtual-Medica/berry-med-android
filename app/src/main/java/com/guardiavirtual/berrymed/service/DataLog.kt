// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

/**
 * Cuándo escribir la línea `[DATA]` de un paquete de la consulta: la primera
 * siempre, después una cada [everyNanos] y siempre que cambien los signos
 * (`vitalSigns`). Antes salía una por segundo y el registro de la tablet se
 * llenaba: de una consulta quedaban los últimos 90 s. Los errores de envío
 * no pasan por acá: salen siempre. Sin Android, para probarla sola.
 */
class DataLog(private val everyNanos: Long = EVERY_NANOS) {
    private var lastAt: Long? = null
    private var lastVitals: String? = null

    /** Empieza una consulta: la próxima línea sale sí o sí. */
    @Synchronized
    fun reset() {
        lastAt = null
        lastVitals = null
    }

    /** Si va la línea del paquete con signos [vitals] en [now]; si va, la cuenta. */
    @Synchronized
    fun due(now: Long, vitals: String): Boolean {
        val last = lastAt
        if (last != null && vitals == lastVitals && now - last < everyNanos) return false
        lastAt = now
        lastVitals = vitals
        return true
    }

    companion object {
        const val EVERY_NANOS = 10L * 1_000_000_000L
    }
}
