// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Espera el primer cuadro del otoscopio antes de publicar su pista: que la
 * fuente abra no quiere decir que mande imagen (libuvc sin superficie abría y
 * no mandaba nada), y una pista vacía le llega al médico como video negro.
 * Sin Android, para probarla sola.
 */
class FirstFrame {
    private val latch = CountDownLatch(1)

    /** Lo llama la fuente con cada cuadro, desde cualquier hilo: sólo cuenta el primero. */
    fun arrived() {
        latch.countDown()
    }

    /** Bloquea hasta el primer cuadro o hasta [timeoutMs]. true si llegó. */
    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

    companion object {
        /** Lo que dice la teleconsulta si la fuente abrió y no llegó ningún cuadro en el plazo. */
        const val NO_IMAGE = "El otoscopio abrió pero no manda imagen"
    }
}
