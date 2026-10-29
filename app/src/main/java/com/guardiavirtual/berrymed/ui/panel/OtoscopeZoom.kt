// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

/**
 * El tamaño del otoscopio ampliado. La imagen es cuadrada, así que manda el
 * lado más corto de la pantalla: en la tablet, apaisada, el alto menos la fila
 * de botones de abajo. Aparte para poder probarlo sin Android.
 */
object OtoscopeZoom {
    /**
     * El lado del cuadrado más grande que entra en [widthPx]×[heightPx] dejando
     * [marginPx] por lado y [buttonsPx] abajo para los botones y el mensaje.
     * Nunca negativo: en una pantalla absurda queda en cero.
     */
    fun stageSide(widthPx: Int, heightPx: Int, marginPx: Int, buttonsPx: Int): Int {
        val byWidth = widthPx - 2 * marginPx
        val byHeight = heightPx - 2 * marginPx - buttonsPx
        return minOf(byWidth, byHeight).coerceAtLeast(0)
    }
}
