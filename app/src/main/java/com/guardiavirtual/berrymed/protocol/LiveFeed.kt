// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.protocol

/**
 * El segundo lector del parser, para el Panel Local. El parser entrega las
 * ondas una sola vez (las vacía en cada envío), así que la pantalla no puede
 * leer de ahí sin robarle muestras al POST. Acá cada muestra se copia a un
 * buffer circular por canal apenas se decodifica; la pantalla lee lo nuevo a
 * su ritmo y el envío no se entera.
 *
 * El hilo del USB escribe y el de la pantalla lee. Cada canal lleva un
 * contador de muestras escritas: la pantalla guarda hasta dónde leyó y pide
 * lo que sigue. Si se atrasó más que la capacidad, pierde lo más viejo.
 */
class LiveFeed {
    class Channel(val capacity: Int) {
        private val ring = IntArray(capacity)
        private val lock = Any()
        private var written = 0L

        fun push(value: Int) = synchronized(lock) {
            ring[(written % capacity).toInt()] = value
            written++
        }

        /** Cuántas muestras se escribieron desde que arrancó. */
        fun count(): Long = synchronized(lock) { written }

        /**
         * Copia en [out] las muestras escritas después de [cursor] y devuelve
         * el cursor nuevo con cuántas copió. Si [cursor] quedó más atrás que la
         * capacidad, empieza desde la más vieja que todavía está.
         */
        fun readSince(cursor: Long, out: IntArray): Read = synchronized(lock) {
            val oldest = maxOf(0L, written - capacity)
            // Un cursor adelante de lo escrito quedó de antes de un clear: se arranca de nuevo.
            var from = if (cursor > written) oldest else maxOf(cursor, oldest)
            val available = (written - from).toInt()
            val n = minOf(available, out.size)
            // Si la pantalla pide menos de lo que hay, se queda con lo más nuevo.
            if (available > n) from = written - n
            for (i in 0 until n) out[i] = ring[((from + i) % capacity).toInt()]
            Read(from + n, n)
        }

        fun clear() = synchronized(lock) {
            written = 0L
        }
    }

    data class Read(val cursor: Long, val count: Int)

    /** Lo que informa la plaqueta de la presión mientras mide. */
    data class NibpState(val status: Int, val cuffMmhg: Int)

    val ecg: Map<String, Channel> = Pm6750Protocol.LEADS.associateWith { Channel(ECG_CAPACITY) }
    val spo2 = Channel(WAVE_CAPACITY)
    val resp = Channel(WAVE_CAPACITY)

    @Volatile var nibp: NibpState = NibpState(status = -1, cuffMmhg = 0)
        private set

    /** Una muestra por derivación, en el orden de [Pm6750Protocol.LEADS]. */
    fun onEcg(samples: IntArray) {
        var i = 0
        for (lead in Pm6750Protocol.LEADS) {
            if (i >= samples.size) break
            ecg.getValue(lead).push(samples[i])
            i++
        }
    }

    fun onSpo2Wave(value: Int) = spo2.push(value)

    fun onRespWave(value: Int) = resp.push(value)

    fun onNibp(status: Int, cuffMmhg: Int) {
        nibp = NibpState(status, cuffMmhg)
    }

    fun clear() {
        ecg.values.forEach(Channel::clear)
        spo2.clear()
        resp.clear()
        nibp = NibpState(status = -1, cuffMmhg = 0)
    }

    companion object {
        /** Diez segundos de ECG a 250 muestras por segundo: más que lo que entra en pantalla. */
        const val ECG_CAPACITY = 2_500
        const val WAVE_CAPACITY = 2_500
    }
}
