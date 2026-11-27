// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.ui.panel.PanelFormat
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Los diccionarios de la app leídos del disco, para los tests de la JVM (sin
 * Android ni Robolectric): `res/values/strings.xml` es el español de origen y
 * `res/values-xx/strings.xml`, cada traducción.
 *
 * Gradle corre los tests con el módulo `app` como carpeta de trabajo.
 */
object TestStrings {
    val resDir = File("src/main/res")

    /** Una entrada del diccionario tal como está escrita en el XML. */
    data class Entry(val name: String, val value: String, val translatable: Boolean)

    private val cache = HashMap<String, Map<String, Entry>>()

    /** id de `R.string` → nombre de la entrada. */
    private val names: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** Las carpetas de traducción que existen: «en», «pt»… (sin la de origen). */
    fun translations(): List<String> =
        resDir.listFiles { file -> file.isDirectory && file.name.startsWith("values-") && File(file, "strings.xml").exists() }
            .orEmpty()
            .map { it.name.removePrefix("values-") }
            .sorted()

    /**
     * El diccionario de [language]; "" es el de origen: `values/strings.xml` más
     * lo fijo de `values/donottranslate.xml` (nombres y unidades, `translatable="false"`).
     */
    fun catalog(language: String = ""): Map<String, Entry> = cache.getOrPut(language) {
        val files =
            if (language.isEmpty()) listOf(File(resDir, "values/strings.xml"), File(resDir, "values/donottranslate.xml")).filter { it.exists() }
            // Un idioma que todavía no tiene carpeta no tiene entradas: todo cae al español.
            else listOf(File(resDir, "values-$language/strings.xml")).filter { it.exists() }
        val entries = LinkedHashMap<String, Entry>()
        for (file in files) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = root.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as Element
                val name = node.getAttribute("name")
                entries[name] = Entry(name, unescape(node.textContent), node.getAttribute("translatable") != "false")
            }
        }
        entries
    }

    /** Lo que Android hace al leer el XML: saca las barras de escape. */
    private fun unescape(raw: String): String =
        raw.trim().replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")

    /** id de `R.plurals` → nombre de la entrada. */
    private val pluralNames: Map<Int, String> by lazy {
        R.plurals::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val pluralCache = HashMap<String, Map<String, Map<String, String>>>()

    /** Las entradas `<plurals>` de [language]: nombre → cantidad («one», «other»…) → texto. */
    fun plurals(language: String = ""): Map<String, Map<String, String>> = pluralCache.getOrPut(language) {
        val file = File(resDir, if (language.isEmpty()) "values/strings.xml" else "values-$language/strings.xml")
        if (!file.exists()) return@getOrPut emptyMap()
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.getElementsByTagName("plurals")
        val out = LinkedHashMap<String, Map<String, String>>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            val items = node.getElementsByTagName("item")
            out[node.getAttribute("name")] = (0 until items.length).associate { j ->
                val item = items.item(j) as Element
                item.getAttribute("quantity") to unescape(item.textContent)
            }
        }
        out
    }

    /**
     * La forma que elige Android para [quantity] con números enteros: «one»
     * para 1 (en portugués también para 0), «many» para los millones exactos
     * en español y portugués, «other» el resto.
     */
    private fun quantityKey(language: String, quantity: Int): String = when {
        quantity == 1 || (language == "pt" && quantity == 0) -> "one"
        (language.isEmpty() || language == "pt") && quantity != 0 && quantity % 1_000_000 == 0 -> "many"
        else -> "other"
    }

    /** El idioma de fechas y números de [language], como lo arma `resolve` en la app. */
    private fun formatLocale(language: String): Locale =
        Locale.forLanguageTag((catalog(language)["fmt_locale"] ?: catalog()["fmt_locale"] ?: error("Falta fmt_locale")).value)

    /** [text] como lo vería alguien en [language]; lo que falta traducir cae al español, como en Android. */
    fun resolve(text: Txt, language: String = ""): String = when (text) {
        is Txt.Raw -> text.value
        is Txt.Res -> {
            val name = names[text.id] ?: error("No hay R.string con id ${text.id}")
            val pattern = (catalog(language)[name] ?: catalog()[name] ?: error("Falta $name en values/strings.xml")).value
            format(pattern, text.args, language)
        }
        is Txt.Plural -> {
            val name = pluralNames[text.id] ?: error("No hay R.plurals con id ${text.id}")
            val forms = plurals(language)[name] ?: plurals()[name] ?: error("Falta el plurals $name en values/strings.xml")
            val pattern = forms[quantityKey(language, text.quantity)] ?: forms["other"] ?: error("$name no tiene other")
            format(pattern, text.args, language)
        }
        is Txt.Join -> text.parts.joinToString(text.separator) { resolve(it, language) }
    }

    private fun format(pattern: String, args: List<Any>, language: String): String {
        if (args.isEmpty()) return pattern
        val values = args.map { if (it is Txt) resolve(it, language) else it }
        return String.format(formatLocale(language), pattern, *values.toTypedArray())
    }
}

/** El texto en español, el idioma de origen: para los tests que afirman lo que se lee en pantalla. */
fun es(text: Txt): String = TestStrings.resolve(text)

/** El texto en [language] («en», «pt»). */
fun Txt.inLanguage(language: String): String = TestStrings.resolve(this, language)

/**
 * Los formatos de fechas y números de [language], leídos del diccionario
 * (`fmt_locale`, `fmt_long_date`, `fmt_short_date_time`) como los arma la pantalla.
 */
fun panelFormatIn(language: String = ""): PanelFormat {
    fun entry(name: String) = (TestStrings.catalog(language)[name] ?: TestStrings.catalog()[name] ?: error("Falta $name")).value
    return PanelFormat(Locale.forLanguageTag(entry("fmt_locale")), entry("fmt_long_date"), entry("fmt_short_date_time"))
}
