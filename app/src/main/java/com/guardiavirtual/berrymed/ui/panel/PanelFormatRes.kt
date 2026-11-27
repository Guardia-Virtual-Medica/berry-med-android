// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.content.Context
import com.guardiavirtual.berrymed.R
import java.util.Locale

/**
 * Los formatos de fechas y números en el idioma de los textos de esta
 * pantalla: el idioma (`fmt_locale`) y los patrones salen del diccionario,
 * no de la configuración de la tablet, para que una fecha nunca salga en
 * otro idioma que el texto que la rodea.
 */
fun Context.panelFormat(): PanelFormat = PanelFormat(
    locale = Locale.forLanguageTag(getString(R.string.fmt_locale)),
    longDatePattern = getString(R.string.fmt_long_date),
    shortDateTimePattern = getString(R.string.fmt_short_date_time),
)
