// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import android.content.Context
import android.content.res.Resources

/** El texto en el idioma de [resources]. */
fun Txt.resolve(resources: Resources): String = when (this) {
    is Txt.Raw -> value
    is Txt.Res -> resources.getString(id, *args.map { if (it is Txt) it.resolve(resources) else it }.toTypedArray())
}

fun Txt.resolve(context: Context): String = resolve(context.resources)
