// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

/**
 * Qué puertos del YKUSH3 prendió el Panel Local para el estetoscopio. Al
 * cerrar el panel se apagan sólo ésos: si un puerto ya estaba encendido (al
 * encender la tablet el hub arranca con los tres prendidos), queda como
 * estaba. Sin Android, para probar la secuencia sola.
 */
class PanelPorts {
    private val owned = LinkedHashSet<Int>()

    /**
     * Los puertos de [wanted] que hay que encender según [states] (un
     * estado por puerto, desde el 1). Un estado desconocido se trata como
     * apagado: si el hub no contestó la lectura, intentar encender no rompe nada.
     */
    fun toTurnOn(wanted: List<Int>, states: List<Boolean?>): List<Int> =
        wanted.distinct().filter { port -> states.getOrNull(port - 1) != true }

    /** El panel encendió [port]: al cerrarse lo tiene que apagar. */
    @Synchronized
    fun turnedOn(port: Int) {
        owned.add(port)
    }

    /** Los puertos que hay que apagar al cerrar el panel, y se olvidan. */
    @Synchronized
    fun takeToTurnOff(): List<Int> {
        val ports = owned.toList()
        owned.clear()
        return ports
    }
}
