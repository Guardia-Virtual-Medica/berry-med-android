// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import com.guardiavirtual.berrymed.tele.KioskUrls

/**
 * Qué muestra Berry Med como pantalla de inicio de la tablet (al prenderla y
 * al tocar Inicio), sin Android. La pantalla de inicio es el kiosco
 * ([KioskActivity], con `HOME` en el manifiesto); con el kiosco sin
 * configurar, pasa a la configuración.
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
