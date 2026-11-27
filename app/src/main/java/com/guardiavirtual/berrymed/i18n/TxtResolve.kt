// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import android.content.Context
import android.content.res.Resources
import com.guardiavirtual.berrymed.R
import java.util.Locale

/**
 * El texto en el idioma de [resources]. Los parámetros se escriben con el
 * idioma de `fmt_locale` de ese mismo diccionario, no con el de la
 * configuración de Android: «hace 1,5 s» sale con coma en español aunque la
 * tablet esté en inglés. Sin parámetros la entrada sale tal cual, como hace
 * `getString(id)`.
 */
fun Txt.resolve(resources: Resources): String = when (this) {
    is Txt.Raw -> value
    is Txt.Res -> format(resources, resources.getString(id), args)
    is Txt.Plural -> format(resources, resources.getQuantityString(id, quantity), args)
    is Txt.Join -> parts.joinToString(separator) { it.resolve(resources) }
}

fun Txt.resolve(context: Context): String = resolve(context.resources)

private fun format(resources: Resources, pattern: String, args: List<Any>): String {
    if (args.isEmpty()) return pattern
    val locale = Locale.forLanguageTag(resources.getString(R.string.fmt_locale))
    return String.format(locale, pattern, *args.map { if (it is Txt) it.resolve(resources) else it }.toTypedArray())
}
