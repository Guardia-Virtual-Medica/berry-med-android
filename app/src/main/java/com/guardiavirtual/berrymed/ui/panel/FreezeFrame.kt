// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.graphics.Bitmap
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * Congelar y Seguir, el mismo mecanismo para la cámara de la tablet y para el
 * otoscopio: la imagen de ese momento queda encima de la vista previa en un
 * `ImageView`. Sólo para mirar con calma: no se guarda en ningún lado.
 */
class FreezeFrame(
    private val image: ImageView,
    private val button: Button,
    private val message: TextView,
    private val freezeText: Int,
    private val resumeText: Int,
    private val frozenText: Int,
) {
    var frozen = false
        private set

    /** Devuelve false si todavía no había imagen para congelar. */
    fun freeze(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        image.setImageBitmap(bitmap)
        image.visibility = View.VISIBLE
        button.setText(resumeText)
        message.setText(frozenText)
        frozen = true
        return true
    }

    fun unfreeze() {
        if (!frozen) return
        image.setImageDrawable(null)
        image.visibility = View.GONE
        button.setText(freezeText)
        message.text = ""
        frozen = false
    }
}
