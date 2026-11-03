// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Desde Android 15 (targetSdk 35) las pantallas van de borde a borde: la
 * barra de estado y la de navegación quedan encima del contenido y su color
 * ya no se elige. Con esto la vista suma a su padding el alto de las barras
 * (y del teclado, que con borde a borde ya no achica la pantalla), así nada
 * queda debajo y detrás de las barras se ve el fondo de la vista.
 *
 * Para la pantalla principal y Testing; el Panel Local y el kiosco esconden
 * las barras.
 */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.updatePadding(
            left = left + bars.left,
            top = top + bars.top,
            right = right + bars.right,
            bottom = bottom + maxOf(bars.bottom, ime.bottom),
        )
        insets
    }
}
