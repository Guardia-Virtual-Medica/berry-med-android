// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import androidx.annotation.StringRes
import com.guardiavirtual.berrymed.R

/**
 * Los idiomas en los que está la app. [tag] es la etiqueta BCP 47 y el sufijo
 * de la carpeta de recursos (`values-en`); el español es el idioma de origen y
 * vive en `values/`. [nativeName] es como se llama el idioma a sí mismo
 * (`donottranslate.xml`): en el selector no se traduce, para que lo encuentre
 * quien no lee el idioma en uso.
 *
 * Sin Android: lo usan el selector, [AppLocale] y los tests.
 */
enum class AppLanguage(val tag: String, @StringRes val nativeName: Int) {
    SPANISH("es", R.string.language_name_es),
    ENGLISH("en", R.string.language_name_en),
    PORTUGUESE("pt", R.string.language_name_pt),
    ;

    companion object {
        /** El idioma de origen: el de `values/` y el que queda mientras nadie elija otro. */
        val DEFAULT = SPANISH

        /**
         * El primero de [tags] (una lista como «pt-BR,en-US») que la app tiene,
         * mirando sólo el idioma y no la región; null si no hay ninguno.
         */
        fun match(tags: String?): AppLanguage? =
            tags.orEmpty().split(',').firstNotNullOfOrNull { tag ->
                val language = tag.trim().substringBefore('-').substringBefore('_').lowercase()
                entries.firstOrNull { it.tag == language }
            }
    }
}
