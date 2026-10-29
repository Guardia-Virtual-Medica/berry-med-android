// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

/**
 * Una grabación del estetoscopio en curso: junta el PCM tal como suena en el
 * auricular (filtrado y con el volumen) hasta [maxSeconds]. Lo que sobra del
 * último bloque se descarta. Sin Android, para probarlo solo.
 *
 * El buffer se pide recién con el primer bloque y [take] lo suelta: 15 s a
 * 48 kHz en 16 bits son 1,44 MB, que no tienen por qué quedar ocupados
 * mientras no se graba.
 */
class StethoRecorder(
    private val sampleRate: Int = BandPass.SAMPLE_RATE,
    maxSeconds: Int = MAX_SECONDS,
) {
    private val capacity = sampleRate * maxSeconds
    private var buffer: ShortArray? = null

    /** Cuántas muestras lleva. */
    var size = 0
        private set

    val isFull: Boolean get() = size >= capacity

    /** Los segundos que lleva grabados. */
    val seconds: Double get() = size.toDouble() / sampleRate

    /** Agrega las primeras [count] muestras de [block] hasta llenarse; devuelve cuántas entraron. */
    fun append(block: ShortArray, count: Int): Int {
        val n = minOf(count, capacity - size, block.size)
        if (n <= 0) return 0
        val target = buffer ?: ShortArray(capacity).also { buffer = it }
        System.arraycopy(block, 0, target, size, n)
        size += n
        return n
    }

    /** Lo grabado, del tamaño justo, y queda vacío para otra grabación. */
    fun take(): ShortArray {
        val out = buffer?.copyOf(size) ?: ShortArray(0)
        buffer = null
        size = 0
        return out
    }

    companion object {
        const val MAX_SECONDS = 15

        /** Una grabación cortada antes de 1 s no sirve para auscultar: se descarta. */
        const val MIN_SECONDS = 1.0
    }
}

/** Una grabación terminada: el sonido, el filtro con que se grabó y la hora. */
class StethoRecording(
    /** Cuántas se grabaron antes, más uno: 1 es la primera de la sesión. */
    val number: Int,
    val filter: StethoFilter,
    val atMillis: Long,
    val samples: ShortArray,
)

/**
 * Las grabaciones de la sesión, sólo en memoria: el Panel Local no guarda
 * nada en disco. Se ven las últimas [keep] y las más viejas se sueltan apenas
 * dejan de verse, así la memoria no crece con la consulta. La numeración
 * sigue aunque se suelten.
 */
class StethoRecordings(private val keep: Int = SHOWN) {
    private val items = ArrayDeque<StethoRecording>()

    /** Las grabadas desde que se abrió o se vació, aunque ya no se vean todas. */
    var count = 0
        private set

    fun add(filter: StethoFilter, atMillis: Long, samples: ShortArray): StethoRecording {
        count++
        val recording = StethoRecording(count, filter, atMillis, samples)
        items.addLast(recording)
        while (items.size > keep) items.removeFirst()
        return recording
    }

    /** De la más vieja a la más nueva. */
    fun latest(): List<StethoRecording> = items.toList()

    fun find(number: Int): StethoRecording? = items.firstOrNull { it.number == number }

    fun clear() {
        items.clear()
        count = 0
    }

    companion object {
        const val SHOWN = 3
    }
}
