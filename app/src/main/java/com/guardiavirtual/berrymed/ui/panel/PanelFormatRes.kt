// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.guardiavirtual.berrymed.R
import java.util.Locale

/**
 * Los formatos de fechas y números en el idioma de los textos de esta
 * pantalla: el idioma (`fmt_locale`) y los patrones salen del diccionario,
 * no de la configuración de la tablet, para que una fecha nunca salga en
 * otro idioma que el texto que la rodea.
 *
 * Un patrón que no se puede leer (una traducción mal escrita, o el
 * seudo-idioma `en-XA`, que también le cambia las letras a los patrones) no
 * cierra la pantalla: se usan los del español.
 */
fun Context.panelFormat(): PanelFormat = try {
    formatFrom(this)
} catch (_: IllegalArgumentException) {
    formatFrom(spanish(this))
}

private fun formatFrom(context: Context): PanelFormat = PanelFormat(
    locale = Locale.forLanguageTag(context.getString(R.string.fmt_locale)),
    longDatePattern = context.getString(R.string.fmt_long_date),
    shortDateTimePattern = context.getString(R.string.fmt_short_date_time),
)

// No cambia el idioma de la app: es un contexto aparte, sólo para leer el español de values/.
@SuppressLint("AppBundleLocaleChanges")
private fun spanish(context: Context): Context =
    context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("es")) })
