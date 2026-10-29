// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.net.PostOutcome

/**
 * La red de seguridad de las órdenes de Pusher. Sin sesión, cada [probeEveryNanos]
 * la tablet prueba con un POST: si la plataforma tiene una toma de signos abierta
 * (200), se perdió el start-monitoring y empieza a enviar igual. Con sesión,
 * [stopAfterNotFound] respuestas 404 seguidas quieren decir que la toma se cerró
 * y el stop-monitoring no llegó.
 *
 * Cada consulta tiene su epoch (el de [SessionGate]): las respuestas de otra
 * consulta no cuentan. Sin eso, los POST que seguían en vuelo de la consulta
 * anterior volvían con 404 después del inicio, dejaban el contador cargado y
 * el primer 404 de la nueva la cortaba.
 */
class SessionWatch(
    private val probeEveryNanos: Long = PROBE_EVERY_NANOS,
    private val pauseAfterStopNanos: Long = PAUSE_AFTER_STOP_NANOS,
    private val stopAfterNotFound: Int = STOP_AFTER_NOT_FOUND,
    private val notFoundGraceNanos: Long = NOT_FOUND_GRACE_NANOS,
) {
    private var nextProbeAt = 0L
    private var notFound = 0
    private var currentEpoch = NO_SESSION
    private var startedAt = 0L

    @Synchronized
    fun probeDue(now: Long): Boolean = now >= nextProbeAt

    @Synchronized
    fun probed(now: Long) {
        nextProbeAt = now + probeEveryNanos
    }

    /** Empezó la consulta [epoch] en [now]: sólo cuentan sus respuestas. */
    @Synchronized
    fun started(epoch: Long, now: Long) {
        notFound = 0
        currentEpoch = epoch
        startedAt = now
    }

    /**
     * Después de un stop-monitoring la plataforma sigue aceptando paquetes un
     * minuto. Probar en ese rato daría 200 y reabriría una toma que se acaba de
     * cerrar, así que la próxima prueba espera [pauseAfterStopNanos].
     */
    @Synchronized
    fun stopped(now: Long, byPlatform: Boolean) {
        notFound = 0
        currentEpoch = NO_SESSION
        nextProbeAt = now + if (byPlatform) pauseAfterStopNanos else probeEveryNanos
    }

    /**
     * Lo que respondió un envío de la consulta [epoch], recibido en [now].
     * Devuelve true si hay que dejar de enviar. Una respuesta de otra consulta
     * no cuenta. Un 404 en los primeros [notFoundGraceNanos] tampoco: la
     * plataforma puede tardar un momento en abrir la toma después del
     * start-monitoring, y cortar ahí perdería unos 10 s hasta la próxima prueba.
     */
    @Synchronized
    fun sent(outcome: PostOutcome, epoch: Long, now: Long): Boolean {
        if (epoch != currentEpoch) return false
        when {
            outcome == PostOutcome.Ok -> notFound = 0
            outcome is PostOutcome.Http && outcome.code == 404 && now - startedAt >= notFoundGraceNanos -> notFound++
        }
        return notFound >= stopAfterNotFound
    }

    companion object {
        const val PROBE_EVERY_NANOS = 10L * 1_000_000_000L

        /** Más que los 60 s que la plataforma sigue aceptando paquetes después del fin. */
        const val PAUSE_AFTER_STOP_NANOS = 90L * 1_000_000_000L

        const val STOP_AFTER_NOT_FOUND = 3

        /** Al empezar la consulta, durante este rato un 404 no cuenta. */
        const val NOT_FOUND_GRACE_NANOS = 5L * 1_000_000_000L

        /** Sin consulta: ningún epoch del gate es negativo. */
        private const val NO_SESSION = -1L
    }
}
