// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

/**
 * Cuándo abrir de nuevo el otoscopio, sin Android. No manda imagen hasta que
 * alguien elige «Computer» en su propia pantalla, y al elegirlo se desenchufa
 * y se vuelve a enchufar solo (otro `deviceId`): lo que se había abierto queda
 * apuntando al de antes y no recibe nada. Lo usan el Panel Local
 * ([UvcOtoscope]) y la teleconsulta ([UvcOtoscopeFrames]).
 */
object OtoscopeReconnect {
    /**
     * [opened] es el `deviceId` que se abrió y [now] el que Android ve
     * ahora (null si no ve el otoscopio). Se reabre sólo si está y es
     * otro: mientras no está (a mitad de la reconexión, o apagado) se espera.
     */
    fun shouldReopen(opened: Int?, now: Int?): Boolean = now != null && opened != null && now != opened

    /** Lo que se espera después de la reconexión antes de abrir: el video todavía no está listo en el primer instante. */
    const val SETTLE_MS = 1_200L
}
