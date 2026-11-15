// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import com.guardiavirtual.berrymed.tele.KioskUrls

/**
 * Si el kiosco se puede mostrar, sin Android. Con el kiosco sin configurar,
 * el kiosco pasa a la configuración y la card ONDOCTOR365 de Inicio
 * ([HomeActivity]) dice que falta la URL.
 */
enum class HomeTarget {
    /** La URL del kiosco guardada es válida: el kiosco. */
    KIOSK,

    /** Sin URL del kiosco: la configuración ([MainActivity]). */
    SETTINGS,

    /** La configuración todavía no se leyó: no se decide con los valores de fábrica. */
    WAIT,
    ;

    companion object {
        fun of(configLoaded: Boolean, kioskUrl: String): HomeTarget = when {
            !configLoaded -> WAIT
            kioskUrl.isNotBlank() && KioskUrls.origin(kioskUrl.trim()) != null -> KIOSK
            else -> SETTINGS
        }
    }
}
