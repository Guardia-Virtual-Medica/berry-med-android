// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

/**
 * Un solo equipo de audio USB encendido a la vez. Android atiende una sola
 * placa de audio USB: el otoscopio trae micrófono propio y, con los dos
 * puertos encendidos, el último que se presenta le saca el lugar al adaptador
 * del estetoscopio (sigue enchufado, pero desaparece de `AudioManager`). Por
 * eso, para usar el otoscopio o el estetoscopio, primero se apaga el puerto
 * del otro. Lo usan la teleconsulta y el Panel Local. Sin Android, para
 * probar la regla sola.
 */
object InstrumentPorts {
    /** Qué puertos apagar ([off]) y cuál encender después ([on], null si ya está encendido). */
    data class Steps(val off: List<Int>, val on: Int?) {
        /** Si algo cambió en el hub: Android tarda un momento en ver (o soltar) las placas de audio. */
        val changes: Boolean get() = off.isNotEmpty() || on != null
    }

    /**
     * Para usar el equipo del puerto [use], con el del otro instrumento en
     * [other] y el termómetro en [thermometer] (que no se toca nunca). [states]
     * es lo leído del hub, un estado por puerto desde el 1; null si no se pudo
     * leer. El otro se apaga si no se leyó apagado: repetir la orden no
     * molesta, y dejarlo encendido le saca el audio al estetoscopio. El propio
     * se enciende si no se leyó encendido. Con los dos en el mismo puerto
     * (configuración rara) no se apaga nada.
     */
    fun forUse(use: Int, other: Int, thermometer: Int, states: List<Boolean?>): Steps {
        val off = if (other != use && other != thermometer && states.getOrNull(other - 1) != false) listOf(other) else emptyList()
        val on = use.takeIf { states.getOrNull(it - 1) != true }
        return Steps(off, on)
    }
}
