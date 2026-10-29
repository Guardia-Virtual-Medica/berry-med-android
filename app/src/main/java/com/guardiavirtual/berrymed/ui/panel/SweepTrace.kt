// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import com.guardiavirtual.berrymed.protocol.LiveFeed

/**
 * Una onda con barrido, como en un monitor: las muestras nuevas se escriben
 * de izquierda a derecha sobre las viejas, y adelante del cursor queda un
 * hueco. No tiene Android adentro: se prueba sola.
 */
class SweepTrace(val samplesPerScreen: Int) {
    /** Una posición por muestra en pantalla; NaN donde todavía no llegó nada. */
    val values = FloatArray(samplesPerScreen) { Float.NaN }

    /** Dónde se escribe la próxima muestra. */
    var head = 0
        private set

    private var cursor = 0L
    private val scratch = IntArray(SCRATCH)

    /** Trae lo nuevo del canal y lo escribe en pantalla. Devuelve cuántas muestras llegaron. */
    fun feed(channel: LiveFeed.Channel): Int {
        val read = channel.readSince(cursor, scratch)
        cursor = read.cursor
        for (i in 0 until read.count) {
            values[head] = scratch[i].toFloat()
            head = (head + 1) % samplesPerScreen
        }
        return read.count
    }

    /** Pantalla en blanco y a leer desde el principio del canal, que se acaba de vaciar. */
    fun reset() {
        values.fill(Float.NaN)
        head = 0
        cursor = 0L
    }

    /**
     * Pantalla en blanco y el barrido de nuevo en x = 0, sin volver a leer
     * lo viejo: al cambiar las derivaciones. Con [skipTo], además, salta lo
     * que llegó mientras no se leía (al reanudar un ECG congelado).
     */
    fun wipe(skipTo: Long? = null) {
        values.fill(Float.NaN)
        head = 0
        if (skipTo != null) cursor = skipTo
    }

    /** Otra cantidad de muestras por pantalla (otra velocidad), en blanco y siguiendo desde donde iba. */
    fun resized(samples: Int): SweepTrace = SweepTrace(samples).also { it.cursor = cursor }

    /**
     * Arma los segmentos para `drawLines`: cuatro números por segmento. No
     * une dos muestras si alguna falta, ni las que quedan dentro del hueco
     * de [gap] muestras adelante del cursor. Devuelve cuántos números cargó.
     */
    fun segments(out: FloatArray, xOf: (Int) -> Float, yOf: (Float) -> Float, gap: Int): Int {
        var n = 0
        val limit = out.size - 4
        for (i in 0 until samplesPerScreen - 1) {
            if (n > limit) break
            if (inGap(i, gap) || inGap(i + 1, gap)) continue
            val a = values[i]
            val b = values[i + 1]
            if (a.isNaN() || b.isNaN()) continue
            out[n] = xOf(i)
            out[n + 1] = yOf(a)
            out[n + 2] = xOf(i + 1)
            out[n + 3] = yOf(b)
            n += 4
        }
        return n
    }

    /** Las [gap] posiciones desde el cursor, con vuelta al principio. */
    fun inGap(index: Int, gap: Int): Boolean {
        val distance = ((index - head) % samplesPerScreen + samplesPerScreen) % samplesPerScreen
        return distance < gap
    }

    /** El mínimo y el máximo de lo que hay en pantalla, para las ondas sin escala fija. */
    fun range(): Pair<Float, Float>? {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        return if (min <= max) min to max else null
    }

    companion object {
        /** Lo más que se trae por cuadro: dos segundos de ECG. Si la pantalla se atrasó más, descarta lo viejo. */
        const val SCRATCH = 512

        /** Cuántas muestras entran en pantalla a una velocidad de papel dada. */
        fun samplesPerScreen(widthPx: Float, pxPerMm: Float, mmPerSecond: Float, samplesPerSecond: Float): Int {
            if (widthPx <= 0f || pxPerMm <= 0f || mmPerSecond <= 0f) return 1
            val seconds = widthPx / pxPerMm / mmPerSecond
            return maxOf(2, (seconds * samplesPerSecond).toInt())
        }
    }
}
