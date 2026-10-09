// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.net.PostOutcome

/**
 * La red de seguridad de las órdenes de Pusher. Sin sesión, cada [probeEveryNanos]
 * la tablet prueba con un POST: si la plataforma tiene una toma de signos abierta
 * (200), se perdió el start-monitoring y empieza a enviar igual. Con sesión,
 * [stopAfterNotFound] respuestas 404 seguidas quieren decir que la toma se cerró
 * y el stop-monitoring no llegó.
 */
class SessionWatch(
    private val probeEveryNanos: Long = PROBE_EVERY_NANOS,
    private val pauseAfterStopNanos: Long = PAUSE_AFTER_STOP_NANOS,
    private val stopAfterNotFound: Int = STOP_AFTER_NOT_FOUND,
) {
    private var nextProbeAt = 0L
    private var notFound = 0

    @Synchronized
    fun probeDue(now: Long): Boolean = now >= nextProbeAt

    @Synchronized
    fun probed(now: Long) {
        nextProbeAt = now + probeEveryNanos
    }

    @Synchronized
    fun started() {
        notFound = 0
    }

    /**
     * Después de un stop-monitoring la plataforma sigue aceptando paquetes un
     * minuto. Probar en ese rato daría 200 y reabriría una toma que se acaba de
     * cerrar, así que la próxima prueba espera [pauseAfterStopNanos].
     */
    @Synchronized
    fun stopped(now: Long, byPlatform: Boolean) {
        notFound = 0
        nextProbeAt = now + if (byPlatform) pauseAfterStopNanos else probeEveryNanos
    }

    /** Lo que respondió un envío con sesión. Devuelve true si hay que dejar de enviar. */
    @Synchronized
    fun sent(outcome: PostOutcome): Boolean {
        when {
            outcome == PostOutcome.Ok -> notFound = 0
            outcome is PostOutcome.Http && outcome.code == 404 -> notFound++
        }
        return notFound >= stopAfterNotFound
    }

    companion object {
        const val PROBE_EVERY_NANOS = 10L * 1_000_000_000L

        /** Más que los 60 s que la plataforma sigue aceptando paquetes después del fin. */
        const val PAUSE_AFTER_STOP_NANOS = 90L * 1_000_000_000L

        const val STOP_AFTER_NOT_FOUND = 3
    }
}
