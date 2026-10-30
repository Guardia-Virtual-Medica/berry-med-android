// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

/**
 * Decide cuándo reafirmar el dispositivo de la llamada. Chromium y Berry Med
 * pueden pisarse: cada `setCommunicationDevice` avisa al otro y, sin freno,
 * quedarían en un ciclo. A lo sumo una reafirmación por [MIN_GAP_NANOS] y, tras
 * [MAX_TRIES] seguidas, se deja de insistir hasta que termine la llamada o
 * cambie el dispositivo elegido.
 *
 * Que el dispositivo vuelva un instante no cuenta como éxito: en un ciclo vuelve
 * siempre. Los intentos se perdonan sólo si el último duró [STABLE_NANOS].
 *
 * Lo que Berry Med fijó se suelta una sola vez al terminar la llamada, como pide
 * Android. Chromium limpia lo suyo, pero no está garantizado que alcance al
 * pedido de Berry Med. Si Berry Med no fijó nada, no se limpia nada.
 *
 * Sin Android y sin reloj propio, para probarla sola. Se llama de un solo hilo.
 */
class CallReassert {
    sealed interface Step {
        data object Nothing : Step
        /** Reafirmar ahora. */
        data object Set : Step
        /** Hay que reafirmar, pero todavía no: volver a preguntar en [nanos]. */
        data class Wait(val nanos: Long) : Step
        /** Se llegó al tope: avisar una vez y no insistir. */
        data object GiveUp : Step
        /** Terminó la llamada y Berry Med había fijado un dispositivo: soltarlo. */
        data object Clear : Step
    }

    private var tries = 0
    private var lastSetAt: Long? = null
    private var gaveUp = false
    private var lastWanted: Int? = null

    /** Sobrevive a [reset]: cambiar la elección en plena llamada no suelta lo fijado. */
    private var fixed = false

    /** Android aceptó el dispositivo: hay un pedido de Berry Med que soltar al terminar. */
    fun applied() {
        fixed = true
    }

    /** La llamada terminó o cambió la elección: se empieza de cero. */
    fun reset() {
        tries = 0
        lastSetAt = null
        gaveUp = false
        lastWanted = null
    }

    fun next(now: Long, inCall: Boolean, wanted: Int?, current: Int?): Step {
        if (!inCall) {
            reset()
            if (!fixed) return Step.Nothing
            fixed = false
            return Step.Clear
        }
        if (wanted == null) return Step.Nothing
        if (wanted != lastWanted) {
            // Otro dispositivo (el auricular volvió, o se fue) es otra historia.
            reset()
            lastWanted = wanted
        }
        if (current == wanted || gaveUp) return Step.Nothing
        val last = lastSetAt
        if (last != null) {
            val since = now - last
            if (since < MIN_GAP_NANOS) return Step.Wait(MIN_GAP_NANOS - since)
            if (since >= STABLE_NANOS) tries = 0
        }
        if (tries >= MAX_TRIES) {
            gaveUp = true
            return Step.GiveUp
        }
        tries++
        lastSetAt = now
        return Step.Set
    }

    companion object {
        const val MIN_GAP_NANOS = 1_000_000_000L
        const val MAX_TRIES = 5
        const val STABLE_NANOS = 10_000_000_000L
    }
}
