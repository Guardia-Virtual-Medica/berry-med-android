// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pasa-banda de 20 a 500 Hz para el estetoscopio: un pasa-altos y un
 * pasa-bajos Butterworth de segundo orden en cascada. Abajo de 20 Hz sólo hay
 * golpes y roce del diafragma; arriba de 500 Hz no hay ruidos cardíacos ni
 * respiratorios útiles y sí zumbido de la placa USB.
 *
 * Trabaja en double: a 48 kHz el pasa-altos de 20 Hz tiene coeficientes muy
 * cerca de 1 y en float se vuelve inestable.
 */
class BandPass(
    sampleRate: Int = SAMPLE_RATE,
    lowHz: Double = LOW_HZ,
    highHz: Double = HIGH_HZ,
) {
    private val highPass = Biquad.highPass(sampleRate, lowHz)
    private val lowPass = Biquad.lowPass(sampleRate, highHz)

    fun process(sample: Double): Double = lowPass.process(highPass.process(sample))

    fun reset() {
        highPass.reset()
        lowPass.reset()
    }

    /** Un filtro de segundo orden (forma directa I), con las fórmulas del "Audio EQ Cookbook" de R. Bristow-Johnson. */
    class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        companion object {
            /** Q de un Butterworth de segundo orden: sin pico en el corte. */
            private val BUTTERWORTH_Q = 1.0 / sqrt(2.0)

            fun lowPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val w0 = 2.0 * PI * cutoffHz / sampleRate
                val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 - cosW0) / 2.0 / a0,
                    b1 = (1.0 - cosW0) / a0,
                    b2 = (1.0 - cosW0) / 2.0 / a0,
                    a1 = -2.0 * cosW0 / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun highPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val w0 = 2.0 * PI * cutoffHz / sampleRate
                val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 + cosW0) / 2.0 / a0,
                    b1 = -(1.0 + cosW0) / a0,
                    b2 = (1.0 + cosW0) / 2.0 / a0,
                    a1 = -2.0 * cosW0 / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val LOW_HZ = 20.0
        const val HIGH_HZ = 500.0
    }
}
