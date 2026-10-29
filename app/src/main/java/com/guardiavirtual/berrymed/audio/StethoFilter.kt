// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

/**
 * Los tres filtros del estetoscopio, como pasa-bandas [BandPass]. Corazón
 * deja los ruidos cardíacos, que casi no pasan de 250 Hz, y saca el roce y
 * la respiración de arriba; Pulmón empieza en 100 Hz para que los latidos no
 * tapen los ruidos respiratorios, que llegan hasta 1 kHz; Amplio deja todo
 * lo útil. Sin Android, para probarlo solo.
 */
enum class StethoFilter(val lowHz: Double, val highHz: Double) {
    HEART(20.0, 250.0),
    LUNG(100.0, 1_000.0),
    WIDE(20.0, 1_000.0),
    ;

    /** Un filtro nuevo de esta banda, en reposo: cambiar de filtro empieza sin la memoria del anterior. */
    fun bandPass(sampleRate: Int = BandPass.SAMPLE_RATE): BandPass = BandPass(sampleRate, lowHz, highHz)

    companion object {
        val DEFAULT = HEART
    }
}
