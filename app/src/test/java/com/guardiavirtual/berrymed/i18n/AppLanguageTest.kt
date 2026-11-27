// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppLanguageTest {
    /** A10: `pt-BR` es portugués; un idioma que la app no tiene no elige nada. */
    @Test
    fun matchesByLanguageNotRegion() {
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.match("pt-BR"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.match("en-US,es-AR"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.match("es_AR"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.match("fr-FR,es-419,en-US"))
        assertNull(AppLanguage.match("fr-FR,de"))
        assertNull(AppLanguage.match(""))
        assertNull(AppLanguage.match(null))
    }

    /** Sin idioma elegido, la app queda en español (no sigue a la tablet). */
    @Test
    fun spanishIsTheDefault() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.DEFAULT)
        assertEquals("es", AppLanguage.DEFAULT.tag)
    }

    /** A9: el selector, las carpetas de recursos y lo que se le declara a Android dicen lo mismo. */
    @Test
    fun theSelectorTheFoldersAndTheManifestAgree() {
        val selector = AppLanguage.entries.map { it.tag }.sorted()

        val folders = (TestStrings.translations() + AppLanguage.DEFAULT.tag).sorted()
        assertEquals(selector, folders, "AppLanguage y las carpetas values-xx")

        val declared = Regex("""<locale\s+android:name="([^"]+)"""")
            .findAll(File(TestStrings.resDir, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }
            .toList()
            .sorted()
        assertEquals(selector, declared, "AppLanguage y res/xml/locales_config.xml")

        val gradle = File("build.gradle.kts").readText()
        val bundled = Regex("""resourceConfigurations \+= listOf\(([^)]*)\)""").find(gradle)
            ?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') }?.sorted()
        assertEquals(selector, bundled, "AppLanguage y resourceConfigurations de build.gradle.kts")

        // El idioma de origen vive en values/: una carpeta values-es le ganaría al LogText, que lee values/.
        assertFalse(File(TestStrings.resDir, "values-es").exists(), "values-es no tiene que existir")
    }

    /** Cada idioma se llama a sí mismo igual en todos los diccionarios: no se traduce. */
    @Test
    fun nativeNamesAreNotTranslated() {
        val names = AppLanguage.entries.map { TestStrings.resolve(txt(it.nativeName)) }
        assertEquals(listOf("Español", "English", "Português"), names)
        for (language in TestStrings.translations()) {
            assertEquals(names, AppLanguage.entries.map { txt(it.nativeName).inLanguage(language) })
        }
    }
}
