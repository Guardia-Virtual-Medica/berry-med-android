// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.CALL_AUDIO_HEADSET
import com.guardiavirtual.berrymed.config.YKUSH_PORTS

/**
 * Cómo quedan los puertos del YKUSH3 cuando nadie los usa. Con el kiosco
 * adelante, otoscopio y estetoscopio encendidos, así Android los ve todo el
 * tiempo y el otoscopio no pierde su permiso USB: la teleconsulta los usa ya
 * encendidos. Encenderlos es sólo darles energía, no publica nada: la
 * transmisión sigue empezando con `start_oth`/`start_steth` y al cortar el
 * puerto queda encendido. Fuera del kiosco (configuración, Testing, Panel
 * Local, otra app) es el reposo: otoscopio y estetoscopio apagados, y el panel
 * los lee apagados, los enciende y los apaga al terminar. En los dos, el
 * auricular encendido sólo si la llamada sale por él. Sin Android, para probar
 * la regla sola.
 */
object HubRestState {
    /**
     * El estado de cada puerto, desde el 1, con el kiosco adelante ([kiosk]) o
     * no. Un puerto libre es null: no hay equipo, queda como está.
     */
    fun wanted(config: BerryConfig, kiosk: Boolean): List<Boolean?> = (1..YKUSH_PORTS).map { port ->
        when (port) {
            config.ykushHeadsetPort -> config.callAudioOutput == CALL_AUDIO_HEADSET
            config.ykushOtoscopePort, config.ykushStethoscopePort -> kiosk
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
     * Si falta aplicar el estado: una vez por hub, porque cada conexión es un
     * `deviceId` nuevo y vuelve a arrancar como lo tenga guardado, y otra vez
     * si cambió la salida de audio o se abrió o se dejó el kiosco.
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
