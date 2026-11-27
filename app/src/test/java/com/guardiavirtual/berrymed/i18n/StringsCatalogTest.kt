// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Los diccionarios entre sí: cada traducción tiene las mismas entradas que el
 * español, con los mismos parámetros, y nada de más.
 *
 * Mientras dura la migración, lo que todavía no se tradujo está anotado en
 * `src/test/resources/i18n/sin-traducir.txt` (una línea «idioma<TAB>entrada»).
 * La lista sólo puede achicarse: una entrada nueva sin traducir falla, y una
 * que ya se tradujo y sigue anotada también. Con la lista vacía, es paridad total.
 */
class StringsCatalogTest {
    private val source = TestStrings.catalog()
    private val pendingFile = File("src/test/resources/i18n/sin-traducir.txt")

    private fun pending(): Set<String> =
        if (!pendingFile.exists()) emptySet()
        else pendingFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    private fun missingNow(): Set<String> = TestStrings.translations().flatMap { language ->
        val translated = TestStrings.catalog(language).keys
        source.values.filter { it.translatable && it.name !in translated }.map { "$language\t${it.name}" }
    }.toSet()

    @Test
    fun everyEntryIsTranslatedOrListedAsPending() {
        val missing = missingNow()
        if (System.getenv("I18N_REGENERAR") == "1") {
            pendingFile.parentFile.mkdirs()
            pendingFile.writeText(
                "# Entradas de values/strings.xml que todavía no están en cada traducción.\n" +
                    "# Sólo se achica. Regenerar: I18N_REGENERAR=1 ./gradlew testDebugUnitTest --tests '*StringsCatalogTest*'\n" +
                    missing.sorted().joinToString("\n", postfix = "\n"),
            )
            return
        }
        val listed = pending()
        assertEquals(emptyList(), (missing - listed).sorted(), "Entradas nuevas sin traducir (traducilas o, si es a propósito, anotalas)")
        assertEquals(emptyList(), (listed - missing).sorted(), "Ya están traducidas: sacalas de sin-traducir.txt")
    }

    @Test
    fun translationsHaveNoEntriesOfTheirOwn() {
        for (language in TestStrings.translations()) {
            val extra = TestStrings.catalog(language).keys - source.keys
            assertEquals(emptySet(), extra, "values-$language tiene entradas que el español no tiene")
        }
    }

    @Test
    fun whatIsNotTranslatableIsNotTranslated() {
        val fixed = source.values.filterNot { it.translatable }.map { it.name }.toSet()
        for (language in TestStrings.translations()) {
            assertEquals(emptySet(), TestStrings.catalog(language).keys intersect fixed, "values-$language traduce algo marcado translatable=false")
        }
    }

    /** `%1$s`, `%2$d`…: los mismos en todos los idiomas, aunque cambien de orden. */
    @Test
    fun translationsKeepTheParameters() {
        for (language in TestStrings.translations()) {
            for ((name, entry) in TestStrings.catalog(language)) {
                val original = source[name] ?: continue
                assertEquals(parameters(original.value), parameters(entry.value), "Parámetros de $name en values-$language")
            }
        }
    }

    /** Con más de un parámetro van numerados (`%1$s`): sin número, un idioma no puede cambiarles el orden. */
    @Test
    fun parametersArePositional() {
        val all = listOf("") + TestStrings.translations()
        for (language in all) {
            for ((name, entry) in TestStrings.catalog(language)) {
                val loose = Regex("""%(?!\d+\$)[sdf.\d]""").findAll(entry.value.replace("%%", "")).count()
                assertEquals(0, loose, "$name (${language.ifEmpty { "es" }}) usa un parámetro sin número: ${entry.value}")
            }
        }
    }

    @Test
    fun nothingIsEmpty() {
        for (language in listOf("") + TestStrings.translations()) {
            val empty = TestStrings.catalog(language).values.filter { it.value.isBlank() }.map { it.name }
            assertTrue(empty.isEmpty(), "Entradas vacías en ${language.ifEmpty { "es" }}: $empty")
        }
    }

    /**
     * Desde la 1.5.1 la app se llama ONDOCTOR365 Suitcase en los tres idiomas, sin traducir.
     * «Berry» sigue valiendo para la plaqueta (PM6750); «Berry Med» como nombre, no.
     */
    @Test
    fun theAppIsCalledOndoctor365Suitcase() {
        assertEquals("ONDOCTOR365 Suitcase", source.getValue("app_name").value)
        for (language in listOf("") + TestStrings.translations()) {
            val texts = TestStrings.catalog(language).values.map { it.name to it.value } +
                TestStrings.plurals(language).flatMap { (name, forms) -> forms.values.map { name to it } }
            val old = texts.filter { (_, value) -> value.contains("Berry Med", ignoreCase = true) }.map { it.first }
            assertEquals(emptyList(), old, "Entradas de ${language.ifEmpty { "es" }} que todavía nombran a la app como Berry Med")
        }
    }

    private fun parameters(value: String): List<String> =
        Regex("""%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""").findAll(value.replace("%%", ""))
            .map { "${it.groupValues[1]}${it.groupValues[2]}" }
            .sorted()
            .toList()
}
