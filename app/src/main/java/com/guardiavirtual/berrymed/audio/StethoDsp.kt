// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Lo que se hace con cada bloque del estetoscopio, aparte del filtro: la
 * ganancia con un recorte suave, la onda reducida para la pantalla y el
 * nivel. Sin Android, para probarlo solo.
 */
object StethoDsp {
    const val FULL_SCALE = 32_767.0

    /** 48 000 muestras por segundo / 96 = 500 por segundo en pantalla, de sobra para ver un latido. */
    const val DECIMATION = 96

    /** El centro de la onda en el [com.guardiavirtual.berrymed.protocol.LiveFeed]: así se dibuja como las de la plaqueta. */
    const val WAVE_CENTER = 32_768

    /** Las ganancias que ofrece la tarjeta, en el orden en que las recorre el botón. */
    val GAINS = intArrayOf(1, 2, 4)

    /** La siguiente ganancia: x1 → x2 → x4 → x1. */
    fun nextGain(current: Int): Int {
        val index = GAINS.indexOf(current)
        return GAINS[(index + 1) % GAINS.size]
    }

    /**
     * Ganancia y recorte suave: hasta la mitad de la escala pasa igual, y de
     * ahí se curva hacia el tope sin llegar. Un recorte duro en el auricular
     * suena como un chasquido cada vez que el diafragma roza algo.
     */
    fun amplify(sample: Double, gain: Int): Double {
        val x = sample * gain
        val knee = FULL_SCALE / 2.0
        val magnitude = abs(x)
        if (magnitude <= knee) return x
        val over = (magnitude - knee) / knee
        val curved = knee + knee * tanh(over)
        return if (x < 0) -curved else curved
    }

    fun toPcm(sample: Double): Short = sample.coerceIn(-FULL_SCALE, FULL_SCALE).toInt().toShort()

    /**
     * Reduce [block] a una muestra cada [DECIMATION]: el valor de mayor
     * módulo de cada tramo, con su signo. Un promedio borraría el golpe del
     * latido, que dura unos pocos milisegundos. Devuelve cuántas escribió en [out].
     */
    fun decimate(block: ShortArray, count: Int, out: IntArray): Int {
        var written = 0
        var start = 0
        while (start + DECIMATION <= count && written < out.size) {
            var peak = 0
            for (i in start until start + DECIMATION) {
                val v = block[i].toInt()
                if (abs(v) > abs(peak)) peak = v
            }
            out[written++] = peak
            start += DECIMATION
        }
        return written
    }

    /** RMS de las primeras [count] muestras, de 0 a 1 respecto de la escala completa. */
    fun rms(block: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = block[i] / FULL_SCALE
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    /** El nivel para la barra, de 0 a 100: de -60 dB (silencio) a 0 dB (escala completa). */
    fun levelPercent(rms: Float): Int {
        if (rms <= 0f) return 0
        val db = 20.0 * log10(rms.toDouble())
        return ((db + 60.0) / 60.0 * 100.0).roundToInt().coerceIn(0, 100)
    }
}
