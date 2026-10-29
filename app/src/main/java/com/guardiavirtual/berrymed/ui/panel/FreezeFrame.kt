// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.guardiavirtual.berrymed.R

/**
 * El visor de video del otoscopio o de la cámara de la tablet, con sus
 * capturas: el mismo mecanismo para los dos. La imagen congelada (o la
 * captura que se está mirando) queda encima del video en un `ImageView`; el
 * video sigue corriendo debajo.
 *
 * Congelar y capturar son dos pasos, como en el diseño: en vivo se congela;
 * congelado se guarda (y vuelve solo al vivo) o se descarta. El modo vive
 * acá y nada más: qué botones corresponden a cada modo lo dice
 * [PanelUi.videoButtons], y esta clase lo aplica.
 *
 * Las capturas viven en memoria mientras el panel está abierto, en una
 * [CaptureList] propia de cada dispositivo, y se descartan con [clear].
 */
class FreezeFrame(
    private val views: Views,
    /** La imagen del video en este momento, o null si todavía no hay. */
    private val grab: () -> Bitmap?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Las vistas de un visor y de su columna de controles. */
    class Views(
        val image: ImageView,
        val badge: TextView,
        val toast: TextView,
        val primary: MaterialButton,
        val help: View,
        /** El botón propio del dispositivo: Apagar o Cambiar cámara. */
        val own: View,
        val discard: View,
        val capturesLabel: TextView,
        val capturesEmpty: View,
        val capturesGrid: View,
        val thumbs: List<Thumb>,
    )

    class Thumb(val root: View, val image: ImageView, val time: TextView)

    var mode = VideoMode.LIVE
        private set

    /** La imagen congelada sin guardar todavía; sólo en [VideoMode.FROZEN]. */
    private var pending: Bitmap? = null

    /** Si el video está andando y se puede congelar; lo dice la actividad en cada refresco. */
    private var canFreeze = false

    // Una captura que se suelta no se vuelve a mostrar: la grilla ya la cambió antes de que se dibuje.
    private val captures = CaptureList<Bitmap>(release = { if (!it.isRecycled) it.recycle() })

    private val hideToast = Runnable { views.toast.visibility = View.GONE }

    init {
        views.thumbs.forEachIndexed { index, thumb ->
            // El fondo redondeado recorta la imagen: sin esto las esquinas quedan en punta.
            thumb.root.clipToOutline = true
            thumb.root.setOnClickListener { review(index) }
        }
        views.primary.setOnClickListener { primary() }
        views.discard.setOnClickListener { unfreeze() }
        apply()
        showCaptures()
    }

    fun setCanFreeze(enabled: Boolean) {
        if (canFreeze == enabled) return
        canFreeze = enabled
        apply()
    }

    /** Vuelve al vivo: descarta la imagen congelada sin guardar o deja de mirar la captura. */
    fun unfreeze() {
        if (mode == VideoMode.LIVE) return
        pending = null
        views.image.setImageDrawable(null)
        views.image.visibility = View.GONE
        mode = VideoMode.LIVE
        apply()
    }

    /** Vuelve al vivo y suelta todas las capturas: al finalizar o al bloquearse el panel. */
    fun clear() {
        unfreeze()
        hideToast.run()
        views.toast.removeCallbacks(hideToast)
        // Primero que ninguna vista las muestre, después se reciclan.
        views.thumbs.forEach { it.image.setImageDrawable(null) }
        captures.clear()
        showCaptures()
    }

    private fun primary() {
        when (mode) {
            VideoMode.LIVE -> freeze()
            VideoMode.FROZEN -> save()
            VideoMode.REVIEW -> Unit
        }
    }

    private fun freeze() {
        val bitmap = grab() ?: return
        pending = bitmap
        show(bitmap, views.image.context.getString(R.string.local_capture_unsaved))
        mode = VideoMode.FROZEN
        apply()
    }

    /**
     * Guarda la imagen congelada con su miniatura y vuelve solo al vivo. La
     * miniatura se hace una vez acá: escalarla en cada dibujo de la grilla
     * sería tener las imágenes completas a la vista y en memoria.
     */
    private fun save() {
        val full = pending ?: return
        val (width, height) = CaptureList.thumbSize(full.width, full.height)
        val thumb = if (width == full.width && height == full.height) full else Bitmap.createScaledBitmap(full, width, height, true)
        val at = clock()
        // unfreeze antes de agregar: suelta la imagen del visor, que pasa a ser la completa de la lista.
        pending = null
        unfreeze()
        captures.add(at, thumb, full)
        showCaptures()
        val toast = views.toast
        toast.text = toast.context.getString(R.string.local_capture_saved, PanelFormat.clockSeconds(at))
        toast.visibility = View.VISIBLE
        toast.removeCallbacks(hideToast)
        toast.postDelayed(hideToast, TOAST_MS)
    }

    /** Muestra en el visor la captura de esa miniatura; si ya no tiene la completa, la miniatura agrandada. */
    private fun review(slot: Int) {
        val capture = captures.latest().getOrNull(slot) ?: return
        pending = null
        val label = views.image.context.getString(R.string.local_capture_at, PanelFormat.clockSeconds(capture.atMillis))
        show(capture.full ?: capture.thumb, label)
        mode = VideoMode.REVIEW
        apply()
    }

    private fun show(bitmap: Bitmap, badge: String) {
        views.image.setImageBitmap(bitmap)
        views.image.visibility = View.VISIBLE
        views.badge.text = badge
    }

    private fun apply() {
        val buttons = PanelUi.videoButtons(mode)
        val primary = views.primary
        primary.visibility = if (buttons.primary == null) View.GONE else View.VISIBLE
        when (buttons.primary) {
            VideoPrimary.FREEZE -> {
                primary.setText(R.string.local_freeze)
                primary.setIconResource(R.drawable.ic_pause)
                primary.isEnabled = canFreeze
            }
            VideoPrimary.SAVE -> {
                primary.setText(R.string.local_capture_save)
                primary.setIconResource(R.drawable.ic_save)
                primary.isEnabled = true
            }
            null -> Unit
        }
        views.help.visibility = if (buttons.help) View.VISIBLE else View.GONE
        views.own.visibility = if (buttons.discard) View.GONE else View.VISIBLE
        views.discard.visibility = if (buttons.discard) View.VISIBLE else View.GONE
        views.badge.visibility = if (mode == VideoMode.LIVE) View.GONE else View.VISIBLE
    }

    private fun showCaptures() {
        val label = views.capturesLabel
        label.text = label.context.getString(R.string.local_captures_count, captures.count)
        val shown = captures.latest()
        views.capturesEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        views.capturesGrid.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        views.thumbs.forEachIndexed { index, thumb ->
            val capture = shown.getOrNull(index)
            if (capture == null) {
                thumb.image.setImageDrawable(null)
                thumb.root.visibility = View.INVISIBLE
            } else {
                val time = PanelFormat.clockSeconds(capture.atMillis)
                thumb.image.setImageBitmap(capture.thumb)
                thumb.time.text = time
                thumb.root.contentDescription = thumb.root.context.getString(R.string.local_capture_thumb, time)
                thumb.root.visibility = View.VISIBLE
            }
        }
    }

    private companion object {
        const val TOAST_MS = 2_200L
    }
}
