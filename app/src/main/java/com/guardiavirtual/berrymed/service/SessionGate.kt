// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

/**
 * Quién tiene la tablet: una consulta que envía, el Panel Local o nadie.
 * Nunca los dos: con el panel abierto se atiende a alguien en persona y no
 * puede salir nada a OnDoctor, y con una consulta enviando el panel no puede
 * cortarle el envío al médico.
 *
 * No tiene Android adentro, así la regla se prueba sola. Tampoco tiene lock
 * propio: [TotemRuntime] la usa dentro del suyo, porque al abrir o cortar una
 * consulta hace más cosas que tienen que quedar en el mismo paso (vaciar el
 * parser, sacar el paquete a enviar). Los campos son volátiles para que la
 * pantalla y el envío los lean sin tomar el lock.
 */
class SessionGate {
    @Volatile var sessionActive: Boolean = false
        private set

    @Volatile var localMode: Boolean = false
        private set

    /** Cambia con cada inicio y cada fin: la respuesta de un POST viejo no decide nada. */
    @Volatile var epoch: Long = 0L
        private set

    /** Abre el Panel Local. Devuelve false si hay una consulta enviando. */
    fun openLocal(): Boolean {
        if (sessionActive) return false
        localMode = true
        return true
    }

    /**
     * Cierra el Panel Local y devuelve si estaba abierto. Es la única
     * operación que puede ir sin el lock: bajar la bandera nunca deja abiertos
     * los dos, y así la pantalla no espera a un inicio de consulta que está
     * escribiendo en el puerto serie.
     */
    fun closeLocal(): Boolean {
        if (!localMode) return false
        localMode = false
        return true
    }

    /** Un start-monitoring. Devuelve false si el Panel Local está abierto. */
    fun startSession(): Boolean {
        if (localMode) return false
        sessionActive = true
        epoch++
        return true
    }

    /**
     * Una prueba aceptada: la plataforma ya tenía la toma abierta. No arranca
     * si ya hay consulta, si el panel está abierto o si la prueba salió antes
     * del último inicio o fin ([sentEpoch] viejo).
     */
    fun resumeSession(sentEpoch: Long): Boolean {
        if (sessionActive || localMode || epoch != sentEpoch) return false
        sessionActive = true
        epoch++
        return true
    }

    /** Corta la consulta. Cambia el epoch aunque no hubiera una, como siempre hizo el servicio. */
    fun stopSession() {
        sessionActive = false
        epoch++
    }
}
