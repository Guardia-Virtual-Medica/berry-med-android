// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

/**
 * Qué cuenta como "el kiosco no se pudo abrir". Sólo el documento principal:
 * la página carga muchas imágenes, scripts e iframes, y que falle uno (un
 * favicon, por ejemplo) no quiere decir que no haya conexión.
 */
object KioskLoadFailure {
    /** Cada cuánto se vuelve a intentar mientras siga fallando. */
    const val RETRY_MS = 10_000L

    /**
     * Sin estado HTTP es un error de conexión; con estado, sólo cuenta un 5xx
     * (el servidor no contesta bien). Un 4xx lo maneja la página.
     */
    fun matters(isMainFrame: Boolean, httpStatus: Int? = null): Boolean =
        isMainFrame && (httpStatus == null || httpStatus >= 500)
}
