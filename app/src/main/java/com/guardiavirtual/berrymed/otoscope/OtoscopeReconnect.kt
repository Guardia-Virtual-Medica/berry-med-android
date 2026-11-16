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

    /**
     * Lo mismo, y además si está el mismo otoscopio pero hace rato que no
     * manda nada: [quietMs] es lo que pasó desde el último cuadro, o desde que
     * se abrió si todavía no llegó ninguno. Pasa cuando el kernel lo reinicia
     * por su cuenta (al probar su interfaz de disco, unos segundos después de
     * enchufarlo): el `deviceId` no cambia, pero lo abierto queda muerto.
     *
     * [stalls] son las veces seguidas que ya se reabrió por esto sin que
     * llegara imagen: cada una espera más ([stallMs]), porque mientras nadie
     * eligió «Computer» tampoco manda nada y el otoscopio no aguanta que lo
     * abran y lo cierren todo el tiempo.
     */
    fun shouldReopen(opened: Int?, now: Int?, quietMs: Long, stalls: Int): Boolean =
        now != null && opened != null && (now != opened || quietMs >= stallMs(stalls))

    /** Cuánto sin cuadros, con el otoscopio enchufado, antes de abrirlo de nuevo: 5 s, 15 s y después cada 30 s. */
    fun stallMs(stalls: Int): Long = when {
        stalls <= 0 -> 5_000L
        stalls == 1 -> 15_000L
        else -> 30_000L
    }

    /**
     * Los cuadros seguidos que hacen falta desde que se abrió para dar por
     * buena la imagen y volver a empezar la cuenta de [stallMs]: al abrir
     * suele llegar uno suelto aunque el otoscopio no esté transmitiendo.
     */
    const val HEALTHY_FRAMES = 15

    /** Por qué se reabre, para el registro. */
    fun why(opened: Int?, now: Int?): String =
        if (shouldReopen(opened, now)) "se reconectó (pasa al elegir «Computer» en su pantalla)" else "dejó de mandar imagen"

    /** Lo que se espera después de la reconexión antes de abrir: el video todavía no está listo en el primer instante. */
    const val SETTLE_MS = 1_200L
}
