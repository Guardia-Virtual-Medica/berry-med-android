// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.view.View

/**
 * Un control segmentado hecho con vistas comunes: el elegido va con
 * `isSelected`, y el fondo y el color del texto salen de sus selectores.
 * Tocar el que ya está elegido no hace nada.
 */
class Segments(
    private val views: List<View>,
    initial: Int,
    private val onSelect: (Int) -> Unit,
) {
    var selected = initial
        private set

    init {
        views.forEachIndexed { index, view ->
            view.isSelected = index == initial
            view.setOnClickListener { select(index) }
        }
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        views.forEachIndexed { i, view -> view.isSelected = i == index }
        onSelect(index)
    }

    fun setEnabled(enabled: Boolean) = views.forEach { it.isEnabled = enabled }
}
