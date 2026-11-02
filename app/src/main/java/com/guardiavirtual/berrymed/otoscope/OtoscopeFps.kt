// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

/**
 * Cuántos cuadros por segundo se le piden al otoscopio y cómo se mide lo que
 * llega. Sin Android, para probarlo solo.
 *
 * El otoscopio ofrece cada tamaño a 30 o a 5 cuadros. libuvc no elige el más
 * rápido: recorre los intervalos en el orden en que los declara la cámara y
 * se queda con el primero que cae dentro del rango pedido. Con un rango de 1
 * a 30 entran los dos, y si la cámara declara primero el de 5 la imagen sale
 * a saltos. Por eso se piden 30 exactos y el rango sólo si la cámara no los
 * tiene.
 */
object OtoscopeFps {
    const val TARGET = 30

    /** Por debajo de esto, sostenido, la imagen se ve trabada y vale una línea en el log. */
    const val LOW = 20

    /** Cuánto tiene que durar la cadencia baja para dejarlo en el log. */
    const val LOW_FOR_NANOS = 5_000_000_000L

    /**
     * Los rangos que se le piden a libuvc, en orden: primero 30 exactos y, si
     * la cámara no los ofrece para ese tamaño, de 1 a 30 (la cadencia la elige
     * ella). Si tampoco, se le pide el tamaño sin rango.
     */
    fun uvcAttempts(): List<IntRange> = listOf(TARGET..TARGET, 1..TARGET)

    /**
     * El rango de Camera2 para `CONTROL_AE_TARGET_FPS_RANGE`, de los que
     * ofrece la cámara: 30 fijo si está; si no, el que incluya 30 con el
     * mínimo más alto (y, a igual mínimo, el máximo más cerca de 30); null si
     * ninguno llega a 30, y entonces no se pide nada.
     */
    fun camera2Range(available: List<Pair<Int, Int>>): Pair<Int, Int>? {
        val reaching = available.filter { (low, high) -> low <= TARGET && high >= TARGET }
        return reaching.firstOrNull { it == TARGET to TARGET }
            ?: reaching.sortedWith(compareBy<Pair<Int, Int>>({ -it.first }, { it.second })).firstOrNull()
    }

    /**
     * Cuenta cuadros y, una vez por segundo, dice la cadencia. [frame] recibe
     * la marca de tiempo de cada cuadro (`System.nanoTime()`) y devuelve la
     * cadencia del último tramo cuando pasó un segundo desde el anterior, o
     * null mientras tanto. No es seguro entre hilos: lo usa un solo hilo.
     */
    class Meter {
        private var since: Long? = null
        private var frames = 0

        fun frame(nanos: Long): Double? {
            val start = since
            if (start == null) {
                since = nanos
                return null
            }
            frames++
            val elapsed = nanos - start
            if (elapsed < SECOND_NANOS) return null
            val rate = frames * SECOND_NANOS.toDouble() / elapsed
            since = nanos
            frames = 0
            return rate
        }
    }

    /**
     * Avisa una sola vez cuando la cadencia queda por debajo de [LOW] más de
     * [LOW_FOR_NANOS] seguidos, para no llenar el log con una línea por
     * segundo. Si la cadencia se recupera vuelve a vigilar desde cero.
     */
    class LowWatch {
        private var lowSince: Long? = null
        private var told = false

        /** true si hay que dejarlo en el log ahora. */
        fun rate(rate: Double, nanos: Long): Boolean {
            if (rate >= LOW) {
                lowSince = null
                told = false
                return false
            }
            val since = lowSince ?: nanos.also { lowSince = it }
            if (told || nanos - since < LOW_FOR_NANOS) return false
            told = true
            return true
        }
    }

    private const val SECOND_NANOS = 1_000_000_000L
}
