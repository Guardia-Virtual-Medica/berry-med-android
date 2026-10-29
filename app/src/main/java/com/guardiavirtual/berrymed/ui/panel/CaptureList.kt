// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

/**
 * Una captura del otoscopio o de la cámara: la miniatura, la imagen completa
 * si todavía la tiene y la hora en que se guardó.
 */
data class Capture<T : Any>(
    /** Cuántas se guardaron antes, más uno: 1 es la primera de la sesión. */
    val number: Int,
    val atMillis: Long,
    val thumb: T,
    /** Sólo la última la conserva; en las anteriores es null y queda la miniatura. */
    val full: T?,
)

/**
 * Las capturas de un dispositivo, sólo en memoria: el Panel Local no guarda
 * nada en disco. Se ven las últimas [keep], así que las más viejas se sueltan
 * apenas dejan de verse.
 *
 * Una imagen de 1600×900 ocupa unos 5,7 MB; con las de la cámara y las del
 * otoscopio de toda una consulta la memoria se iría. Por eso sólo la última
 * conserva la imagen completa: al llegar otra, la anterior se queda con la
 * miniatura y la completa se suelta con [release]. Sin Android para poder
 * probarla: en el panel [T] es un `Bitmap` y [release] lo recicla.
 */
class CaptureList<T : Any>(
    private val keep: Int = SHOWN,
    private val release: (T) -> Unit = {},
) {
    private val items = ArrayDeque<Capture<T>>()

    /** Las guardadas desde que se abrió o se vació, aunque ya no se vean todas. */
    var count = 0
        private set

    fun add(atMillis: Long, thumb: T, full: T): Capture<T> {
        items.removeLastOrNull()?.let { previous ->
            items.addLast(previous.copy(full = null))
            previous.full?.let { if (it !== previous.thumb) release(it) }
        }
        count++
        val capture = Capture(count, atMillis, thumb, full)
        items.addLast(capture)
        while (items.size > keep) drop(items.removeFirst())
        return capture
    }

    /** Las últimas [n], de la más vieja a la más nueva. */
    fun latest(n: Int = keep): List<Capture<T>> = items.toList().takeLast(n)

    fun clear() {
        while (items.isNotEmpty()) drop(items.removeFirst())
        count = 0
    }

    private fun drop(capture: Capture<T>) {
        capture.full?.let { if (it !== capture.thumb) release(it) }
        release(capture.thumb)
    }

    companion object {
        /** Las miniaturas que entran en la grilla de 2 × 2. */
        const val SHOWN = 4

        /** El lado mayor de una miniatura, en px. */
        const val THUMB_SIDE = 320

        /**
         * El tamaño de la miniatura de una imagen de [width] × [height]: el
         * lado mayor queda en [side] y se respeta la proporción. Una imagen
         * que ya es chica queda como está (y la miniatura es la misma imagen).
         */
        fun thumbSize(width: Int, height: Int, side: Int = THUMB_SIDE): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= side) return width to height
            val scale = side.toDouble() / longest
            return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
        }
    }
}
