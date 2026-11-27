// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Un texto para mostrar, todavía sin idioma. Lo arman las reglas que no
 * conocen Android (los modelos de las pantallas, el runtime) y lo resuelve
 * quien lo muestra, con los recursos del idioma en uso (`resolve`).
 *
 * Así la regla sigue siendo pura y con test, y el texto cambia con el idioma
 * sin volver a calcularla. Dos [Txt] iguales son el mismo texto: las reglas
 * que comparaban cadenas («si dice Falta permiso…») comparan la clave.
 *
 * Los números entran como número, no ya formateados: la entrada dice cómo
 * (`%1$.1f`) y `resolve` los escribe con el idioma de `fmt_locale` del mismo
 * diccionario, así el decimal acompaña al texto y no a la tablet.
 */
sealed interface Txt {
    /** Una entrada del diccionario, con sus parámetros (`%1$s`); un parámetro puede ser otro [Txt]. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : Txt

    /**
     * Una entrada `<plurals>`: [quantity] elige la forma (`one`, `other`…) según
     * las reglas de cada idioma; la cantidad, si se muestra, va también en [args].
     */
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any> = emptyList()) : Txt

    /**
     * Frases enteras, cada una su propia entrada, una al lado de la otra: el
     * renglón de estado de la pantalla principal, una lista «a, b, c». El
     * separador es puntuación (un espacio, «, »), no palabras.
     */
    data class Join(val parts: List<Txt>, val separator: String) : Txt

    /** Lo que no se traduce: el nombre de una red Wi-Fi, un número ya formateado, un modelo. */
    data class Raw(val value: String) : Txt
}

fun txt(@StringRes id: Int, vararg args: Any): Txt = Txt.Res(id, args.toList())

fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): Txt = Txt.Plural(id, quantity, args.toList())

fun join(parts: List<Txt>, separator: String): Txt = Txt.Join(parts, separator)

fun raw(value: String): Txt = Txt.Raw(value)
