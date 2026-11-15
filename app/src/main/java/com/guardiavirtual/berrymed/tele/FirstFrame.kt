// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Espera el primer cuadro del otoscopio antes de publicar su pista: que la
 * fuente abra no quiere decir que mande imagen (el otoscopio manda recién
 * cuando alguien elige «Computer» en su pantalla), y una pista vacía le llega
 * al médico como video negro. Sin Android, para probarla sola.
 */
class FirstFrame {
    private val latch = CountDownLatch(1)

    /** Lo llama la fuente con cada cuadro, desde cualquier hilo: sólo cuenta el primero. */
    fun arrived() {
        latch.countDown()
    }

    /** Bloquea hasta el primer cuadro o hasta [timeoutMs]. true si llegó. */
    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Qué hacer en cada vuelta de la espera. */
    enum class Step {
        /** Llegó imagen: se publica. */
        PUBLISH,

        /** Un `stop_oth`, otra orden o el cierre de la sala cortaron la espera. */
        CANCELLED,

        /** Pasó el tope sin imagen. */
        NO_IMAGE,

        /** El otoscopio se reconectó con otro `deviceId`: se suelta y se abre de nuevo. */
        REOPEN,

        /** Seguir esperando, también si el otoscopio desapareció: vuelve cuando lo prenden. */
        WAIT,
    }

    companion object {
        /** Lo que dice la teleconsulta si la fuente abrió y no llegó ningún cuadro en el plazo. */
        const val NO_IMAGE = "El otoscopio abrió pero no manda imagen"

        /** Lo que dice la teleconsulta si se cortó la espera. */
        const val CANCELLED = "se cortó la espera"

        /**
         * Una vuelta de la espera: cortar gana a todo, después la imagen, el
         * tope ([now] contra [deadline]) y la reconexión ([reconnected]).
         */
        fun next(arrived: Boolean, cancelled: Boolean, now: Long, deadline: Long, reconnected: Boolean): Step = when {
            cancelled -> Step.CANCELLED
            arrived -> Step.PUBLISH
            now >= deadline -> Step.NO_IMAGE
            reconnected -> Step.REOPEN
            else -> Step.WAIT
        }
    }
}
