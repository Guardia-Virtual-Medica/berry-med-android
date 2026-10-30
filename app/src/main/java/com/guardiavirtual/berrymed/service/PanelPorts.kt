// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

/**
 * Qué puertos del YKUSH3 prendió el Panel Local, uno para el estetoscopio y
 * otro para el otoscopio. Al apagar se apagan sólo ésos: si un puerto ya estaba
 * encendido (el auricular en reposo, o un hub que todavía no se llevó al estado
 * de reposo), queda como estaba. Sin Android, para probar la secuencia sola.
 */
class PanelPorts {
    private val owned = LinkedHashSet<Int>()

    /**
     * Los puertos de [wanted] que hay que encender según [states] (un
     * estado por puerto, desde el 1). Un estado desconocido (el hub no
     * contestó la lectura) también se enciende, para que el equipo aparezca;
     * pero ese puerto no se anota como del panel: ver [claim].
     */
    fun toTurnOn(wanted: List<Int>, states: List<Boolean?>): List<Int> =
        wanted.distinct().filter { port -> states.getOrNull(port - 1) != true }

    /**
     * El panel encendió [port], que antes de encenderlo se leyó como
     * [previousState]. Se anota para apagarlo al cerrar sólo si se leyó
     * apagado de verdad. Si la lectura falló pudo estar encendido desde el
     * arranque, con la llamada usándolo: apagarlo al salir lo desenchufaría.
     * Es preferible dejar un puerto prendido de más.
     * Devuelve si quedó anotado.
     */
    @Synchronized
    fun claim(port: Int, previousState: Boolean?): Boolean {
        if (previousState != false) return false
        owned.add(port)
        return true
    }

    /** Los puertos que hay que apagar al cerrar el panel, y se olvidan. */
    @Synchronized
    fun takeToTurnOff(): List<Int> {
        val ports = owned.toList()
        owned.clear()
        return ports
    }
}
