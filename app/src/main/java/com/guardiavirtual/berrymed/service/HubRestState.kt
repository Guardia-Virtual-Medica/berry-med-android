// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.CALL_AUDIO_HEADSET
import com.guardiavirtual.berrymed.config.YKUSH_PORTS

/**
 * Cómo quedan los puertos del YKUSH3 cuando nadie los usa: otoscopio y
 * estetoscopio apagados, y el auricular encendido sólo si la llamada sale por
 * él. El panel y la teleconsulta los leen apagados, los encienden y los apagan
 * al terminar. Sin Android, para probar la regla sola.
 */
object HubRestState {
    /** El estado de cada puerto, desde el 1. Un puerto libre es null: no hay equipo, queda como está. */
    fun wanted(config: BerryConfig): List<Boolean?> = (1..YKUSH_PORTS).map { port ->
        when (port) {
            config.ykushHeadsetPort -> config.callAudioOutput == CALL_AUDIO_HEADSET
            config.ykushOtoscopePort, config.ykushStethoscopePort -> false
            else -> null
        }
    }

    /**
     * Las órdenes que hacen falta, puerto y encendido. Lo que ya está como se
     * quiere no se toca; lo que no se pudo leer se manda igual, porque repetir
     * la orden no molesta.
     */
    fun changes(wanted: List<Boolean?>, states: List<Boolean?>): List<Pair<Int, Boolean>> =
        wanted.mapIndexedNotNull { index, on ->
            if (on == null || states.getOrNull(index) == on) null else index + 1 to on
        }

    /** Para el log y para Testing, como "puerto 1 apagado, puerto 3 encendido". */
    fun describe(wanted: List<Boolean?>): String =
        wanted.mapIndexedNotNull { index, on ->
            on?.let { "puerto ${index + 1} ${if (it) "encendido" else "apagado"}" }
        }.joinToString(", ")

    /**
     * Si falta aplicar el reposo: una vez por hub, porque cada conexión es un
     * `deviceId` nuevo y vuelve a arrancar como lo tenga guardado, y otra vez
     * si cambió la salida de audio.
     */
    class Pending {
        private var doneFor: Int? = null

        @Synchronized
        fun due(deviceId: Int?): Boolean = deviceId != null && deviceId != doneFor

        @Synchronized
        fun done(deviceId: Int?) {
            doneFor = deviceId
        }

        @Synchronized
        fun again() {
            doneFor = null
        }
    }
}
