// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import com.guardiavirtual.berrymed.R
import java.io.File
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
            else listOf(File(resDir, "values-$language/strings.xml"))
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

    /** [text] como lo vería alguien en [language]; lo que falta traducir cae al español, como en Android. */
    fun resolve(text: Txt, language: String = ""): String = when (text) {
        is Txt.Raw -> text.value
        is Txt.Res -> {
            val name = names[text.id] ?: error("No hay R.string con id ${text.id}")
            val pattern = (catalog(language)[name] ?: catalog()[name] ?: error("Falta $name en values/strings.xml")).value
            val args = text.args.map { if (it is Txt) resolve(it, language) else it }
            if (args.isEmpty()) pattern else String.format(pattern, *args.toTypedArray())
        }
    }
}

/** El texto en español, el idioma de origen: para los tests que afirman lo que se lee en pantalla. */
fun es(text: Txt): String = TestStrings.resolve(text)

/** El texto en [language] («en», «pt»). */
fun Txt.inLanguage(language: String): String = TestStrings.resolve(this, language)
