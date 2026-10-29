// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.graphics.Bitmap
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Congelar y Seguir, el mismo mecanismo para la cámara de la tablet y para el
 * otoscopio: la imagen de ese momento queda encima de la vista previa en un
 * `ImageView`. Sólo para mirar con calma: no se guarda en ningún lado.
 *
 * Puede tener más de un botón y más de un mensaje, así el estado congelado
 * vive en un solo lugar. Si los botones tienen ícono, pasa de pausa a play; y
 * el [badge] ("CONGELADO · 10:12:09") se muestra mientras está congelado:
 * quien lo usa le pone el texto antes de congelar.
 */
class FreezeFrame(
    private val image: ImageView,
    private val buttons: List<Button>,
    private val messages: List<TextView>,
    private val freezeText: Int,
    private val resumeText: Int,
    private val frozenText: Int,
    private val badge: View? = null,
    private val freezeIcon: Int = 0,
    private val resumeIcon: Int = 0,
) {
    var frozen = false
        private set

    /** Devuelve false si todavía no había imagen para congelar. */
    fun freeze(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        image.setImageBitmap(bitmap)
        image.visibility = View.VISIBLE
        buttons.forEach { button ->
            button.setText(resumeText)
            if (resumeIcon != 0) (button as? MaterialButton)?.setIconResource(resumeIcon)
        }
        messages.forEach { it.setText(frozenText) }
        badge?.visibility = View.VISIBLE
        frozen = true
        return true
    }

    fun unfreeze() {
        if (!frozen) return
        image.setImageDrawable(null)
        image.visibility = View.GONE
        buttons.forEach { button ->
            button.setText(freezeText)
            if (freezeIcon != 0) (button as? MaterialButton)?.setIconResource(freezeIcon)
        }
        messages.forEach { it.text = "" }
        badge?.visibility = View.GONE
        frozen = false
    }
}
