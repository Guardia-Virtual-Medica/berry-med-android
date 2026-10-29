// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import com.guardiavirtual.berrymed.audio.BandPass
import com.guardiavirtual.berrymed.audio.StethoDsp
import com.guardiavirtual.berrymed.audio.StethoFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lo que se le hace al audio del estetoscopio antes de que WebRTC lo
 * codifique: el filtro Amplio (20 a 1000 Hz) y la ganancia del volumen por
 * defecto, igual que en el Panel Local, y el nivel para Testing. Trabaja
 * sobre el buffer de captura de WebRTC (PCM de 16 bits en el orden de la
 * máquina), en el lugar. Sin Android, para probarlo solo.
 */
class StethoStreamDsp(
    private val filter: StethoFilter = StethoFilter.WIDE,
    volume: Int = StethoDsp.DEFAULT_VOLUME,
) {
    private val gain = StethoDsp.volumeGain(volume)
    private var sampleRate = 0
    private var bandPass: BandPass? = null

    /** RMS del último bloque, de 0 a 1. */
    @Volatile var level: Float = 0f
        private set

    /** Bloques procesados; sirve para ver en Testing que la captura corre. */
    @Volatile var blocks: Long = 0
        private set

    /** Empieza de nuevo: sin la memoria del filtro de una publicación anterior. */
    @Synchronized
    fun reset() {
        bandPass = null
        sampleRate = 0
        level = 0f
    }

    /**
     * Filtra los primeros [bytes] de [buffer], mono a [rate] Hz. Con más de
     * un canal no filtra (el adaptador se abre en mono): sólo mide.
     */
    @Synchronized
    fun process(buffer: ByteBuffer, bytes: Int, rate: Int, channels: Int) {
        val pcm = buffer.duplicate().order(ByteOrder.nativeOrder())
        val count = minOf(bytes, pcm.capacity()) / 2
        if (count <= 0) return
        if (channels == 1 && rate > 0) {
            val band = bandPass?.takeIf { rate == sampleRate } ?: filter.bandPass(rate).also {
                bandPass = it
                sampleRate = rate
            }
            for (i in 0 until count) {
                val sample = pcm.getShort(i * 2).toDouble()
                pcm.putShort(i * 2, StethoDsp.toPcm(StethoDsp.amplify(band.process(sample), gain)))
            }
        }
        var sum = 0.0
        for (i in 0 until count) {
            val v = pcm.getShort(i * 2) / StethoDsp.FULL_SCALE
            sum += v * v
        }
        level = kotlin.math.sqrt(sum / count).toFloat()
        blocks++
    }
}
