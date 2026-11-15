// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

/**
 * Cuándo mostrar el aviso «Sin conexión», sin Android. Lo pregunta la
 * pantalla en cada refresco (medio segundo):
 *
 * - aparece después de [graceMs] seguidos sin Wi-Fi: al prender la tablet la
 *   red tarda en subir y no hace falta asustar a nadie;
 * - una vez por apertura de la pantalla ([restart] en `onStart`), no en cada
 *   refresco: «Ahora no» lo deja cerrado;
 * - se cierra solo cuando llega la red;
 * - no aparece arriba de otra cosa ([blocked]: la pantalla del PIN del Panel
 *   Local, el diálogo del PIN del kiosco o el de cambio de modo). Espera a
 *   que se cierre.
 */
class NoWifiPrompt(private val graceMs: Long = GRACE_MS) {
    enum class Step { SHOW, HIDE, NOTHING }

    private var offlineSince: Long? = null
    private var shown = false

    /** La pantalla se abrió (o volvió al frente): el aviso se puede mostrar otra vez y la espera empieza de nuevo. */
    fun restart() {
        offlineSince = null
        shown = false
    }

    /**
     * Qué hacer ahora. [connected]: hay Wi-Fi. [showing]: el aviso está en
     * pantalla. [blocked]: hay otra cosa arriba.
     */
    fun next(now: Long, connected: Boolean, showing: Boolean, blocked: Boolean): Step {
        if (connected) {
            offlineSince = null
            return if (showing) Step.HIDE else Step.NOTHING
        }
        val since = offlineSince ?: now.also { offlineSince = it }
        if (showing || shown || blocked || now - since < graceMs) return Step.NOTHING
        shown = true
        return Step.SHOW
    }

    companion object {
        /** Lo que se espera sin red antes de avisar. */
        const val GRACE_MS = 5_000L
    }
}
