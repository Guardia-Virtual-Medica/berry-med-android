// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import kotlin.math.roundToInt

/** El ícono de la barra de volumen: alto, bajo o silenciado. */
enum class VolumeIcon { UP, DOWN, OFF }

/**
 * La barra de volumen del panel lateral, sin Android. Android da el volumen
 * de medios como un índice de 0 a un máximo que cambia según la tablet (15,
 * 25…); la barra muestra y arrastra un porcentaje.
 */
object VolumeBar {
    /** Del índice de Android al porcentaje que se muestra. */
    fun percent(index: Int, max: Int): Int =
        if (max <= 0) 0 else (index.coerceIn(0, max) * 100.0 / max).roundToInt()

    /** Silenciado en 0; bajo hasta la mitad; alto desde la mitad. */
    fun icon(index: Int, max: Int): VolumeIcon = when {
        index <= 0 || max <= 0 -> VolumeIcon.OFF
        percent(index, max) < 50 -> VolumeIcon.DOWN
        else -> VolumeIcon.UP
    }

    /** Del porcentaje arrastrado al índice más cercano de Android. */
    fun index(percent: Int, max: Int): Int =
        if (max <= 0) 0 else (percent.coerceIn(0, 100) * max / 100.0).roundToInt()
}
