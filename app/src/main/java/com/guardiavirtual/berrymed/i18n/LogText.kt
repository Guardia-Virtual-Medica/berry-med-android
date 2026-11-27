// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Los mensajes que van al log, siempre en español, sea cual sea el idioma de
 * la pantalla: soporte lee un solo idioma y el README cita líneas textuales.
 *
 * Es un contexto fijo en `es`: sin carpeta `values-es`, Android toma
 * `values/`, que es el español de origen, y lo sigue tomando aunque lleguen
 * `values-en` y `values-pt`. Lo arma una vez el runtime; a las clases sin
 * Android se les pasa [resolve] como función, igual que reciben `log`.
 */
class LogText(context: Context) {
    // No cambia el idioma de la app: es un contexto aparte, sólo para leer el español de values/.
    @SuppressLint("AppBundleLocaleChanges")
    private val resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale("es")) },
    ).resources

    fun resolve(text: Txt): String = text.resolve(resources)
}
