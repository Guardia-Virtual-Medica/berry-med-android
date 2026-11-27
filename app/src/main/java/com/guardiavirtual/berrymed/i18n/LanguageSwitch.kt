// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.R

/**
 * Cuándo no se cambia el idioma: con una consulta enviando o la teleconsulta
 * en una sala. Cambiarlo recrea las pantallas, el kiosco recarga la web y se
 * cortaría la videollamada. Es la misma regla que el cambio de modo
 * ([com.guardiavirtual.berrymed.service.StatusSnapshot.modeSwitchBlock]).
 */
object LanguageSwitch {
    val BLOCKED = txt(R.string.language_blocked)

    /** El motivo para mostrar debajo del botón, o null si se puede cambiar. */
    fun blocked(sessionActive: Boolean, teleInRoom: Boolean): Txt? =
        if (sessionActive || teleInRoom) BLOCKED else null
}
