// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import androidx.annotation.StringRes

/**
 * Un texto para mostrar, todavía sin idioma. Lo arman las reglas que no
 * conocen Android (los modelos de las pantallas, el runtime) y lo resuelve
 * quien lo muestra, con los recursos del idioma en uso (`resolve`).
 *
 * Así la regla sigue siendo pura y con test, y el texto cambia con el idioma
 * sin volver a calcularla. Dos [Txt] iguales son el mismo texto: las reglas
 * que comparaban cadenas («si dice Falta permiso…») comparan la clave.
 */
sealed interface Txt {
    /** Una entrada del diccionario, con sus parámetros (`%1$s`); un parámetro puede ser otro [Txt]. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : Txt

    /** Lo que no se traduce: el nombre de una red Wi-Fi, un número ya formateado, un modelo. */
    data class Raw(val value: String) : Txt
}

fun txt(@StringRes id: Int, vararg args: Any): Txt = Txt.Res(id, args.toList())

fun raw(value: String): Txt = Txt.Raw(value)
