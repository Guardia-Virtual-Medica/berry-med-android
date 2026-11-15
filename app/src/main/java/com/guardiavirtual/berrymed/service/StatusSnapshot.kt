// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.net.WifiState

/**
 * Lo que dibuja el panel lateral en cada refresco. Lo arma
 * [TotemRuntime.statusSnapshot] sin efectos: sólo lee lo que la app ya sabe.
 */
data class StatusSnapshot(
    val local: LocalSnapshot,
    val wifi: WifiState,
    /** Si está enchufado el YKUSH3, tenga permiso o no. */
    val ykushPlugged: Boolean,
    /** Si a algún equipo del tótem le falta el permiso USB. */
    val usbPermissionMissing: Boolean,
    val localMode: Boolean,
    val sessionActive: Boolean,
    /** Si la teleconsulta está en una sala de LiveKit (conectando, conectada o con error). */
    val teleInRoom: Boolean,
    /** El ajuste guardado: "auricular" o "parlante". */
    val callAudioChoice: String,
    /** Si hay algo enchufado en el jack de la tablet. */
    val jackPresent: Boolean,
) {
    companion object {
        const val MODE_SWITCH_BLOCKED = "Hay una consulta en curso. Terminala para cambiar."

        /** Con una consulta enviando o la teleconsulta en una sala no se cambia de modo. */
        fun modeSwitchBlock(sessionActive: Boolean, teleInRoom: Boolean): String? =
            if (sessionActive || teleInRoom) MODE_SWITCH_BLOCKED else null
    }
}
