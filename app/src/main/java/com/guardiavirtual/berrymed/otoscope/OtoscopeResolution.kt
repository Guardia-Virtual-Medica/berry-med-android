// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import kotlin.math.abs

/**
 * Qué tamaño de imagen se le pide al otoscopio: el más cercano a 800×800. A
 * 1440² la cámara reserva casi todo el ancho de banda del USB 2.0 y deja sin
 * lugar a la plaqueta, que cuelga del mismo dock. Sin Android, para probarlo solo.
 */
object OtoscopeResolution {
    const val TARGET = 800

    data class Size(val width: Int, val height: Int)

    /**
     * Las de [sizes] de la más conveniente a la menos: primero las cuadradas
     * (el otoscopio manda en cuadrado y la tarjeta es cuadrada), después la
     * más cercana a 800×800 y, a igual distancia, la más chica.
     */
    fun byPreference(sizes: List<Size>): List<Size> = sizes.sortedWith(
        compareBy<Size>(
            { if (it.width == it.height) 0 else 1 },
            { abs(it.width - TARGET) + abs(it.height - TARGET) },
            { it.width * it.height },
        ),
    )

    fun best(sizes: List<Size>): Size? = byPreference(sizes).firstOrNull()
}
