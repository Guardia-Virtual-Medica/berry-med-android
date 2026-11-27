// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Que no vuelva a entrar un texto escrito en el código.
 *
 * Lee los fuentes de `src/main` y junta todo literal que parece una frase
 * para una persona. Cada uno tiene que estar en `src/test/resources/i18n/textos-en-codigo.txt`,
 * en una de dos listas:
 *
 * - **PENDIENTE**: falta pasarlo al diccionario. La migración la lleva a cero.
 * - **PERMITIDO**: se queda en el código a propósito (motivos de los logs,
 *   nombres de protocolo), revisado uno por uno.
 *
 * Las líneas de log, que empiezan con su etiqueta (`[USB] …`), no cuentan: el
 * log va siempre en español. Un texto nuevo que no está en ninguna lista falla
 * el test; uno que ya se sacó del código y sigue anotado, también: la lista
 * sólo se achica.
 */
class HardcodedTextTest {
    private val sources = File("src/main/java")
    private val layouts = File("src/main/res/layout")
    private val listFile = File("src/test/resources/i18n/textos-en-codigo.txt")

    /** Un literal de Kotlin: dónde está y qué dice, con `${…}` reducido a «{}». */
    data class Found(val file: String, val text: String) {
        override fun toString() = "$file\t$text"
    }

    @Test
    fun noNewHardcodedTextInKotlin() {
        val found = kotlinFiles().flatMap { file -> literals(file.readText()).filter(::looksLikeProse).map { Found(relative(file), it) } }.toSet()
        if (System.getenv("I18N_REGENERAR") == "1") {
            regenerate(found)
            return
        }
        val (pending, allowed) = readLists()
        val known = pending + allowed
        val now = found.map { it.toString() }.toSet()
        assertEquals(emptyList(), (now - known).sorted(), "Textos nuevos escritos en el código: van al diccionario (strings.xml)")
        assertEquals(emptyList(), (known - now).sorted(), "Ya no están en el código: sacalos de textos-en-codigo.txt")
    }

    /** En los layouts no hay listas: un texto con letras va a `@string/…` (o a `tools:text` si es de muestra). */
    @Test
    fun layoutsHaveNoHardcodedText() {
        val attribute = Regex("""android:(text|hint|contentDescription|title|label|tooltipText)="([^"@?][^"]*)"""")
        val found = layouts.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }.flatMap { file ->
            attribute.findAll(file.readText())
                .map { it.groupValues[2] }
                // Un número solo no se traduce, pero con coma o punto decimal sí cambia con el idioma.
                .filter { value -> value.any { it.isLetter() } || Regex("""\d[.,]\d""").containsMatchIn(value) }
                .map { "${file.name}\t$it" }
                .toList()
        }
        val allowed = readSection("LAYOUTS")
        assertEquals(emptyList(), (found.toSet() - allowed).sorted(), "Textos escritos en un layout")
        assertEquals(emptyList(), (allowed - found.toSet()).sorted(), "Ya no están en los layouts: sacalos de textos-en-codigo.txt")
    }

    /** Una palabra sola con mayúscula también es una frase, termine o no en un signo. */
    @Test
    fun oneCapitalizedWordWithPunctuationIsProse() {
        listOf("Conectado", "Escuchando.", "Detenido.", "Grabando.", "Encendiendo…", "Atención:", "Listo!", "Listo?")
            .forEach { assertTrue(looksLikeProse(it), it) }
        listOf("berry-usb", "start-monitoring", "HH:mm", "OK.", "[USB] Conectado.")
            .forEach { assertFalse(looksLikeProse(it), it) }
    }

    private fun kotlinFiles(): List<File> = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun relative(file: File): String = file.relativeTo(sources).path.substringAfter("com/guardiavirtual/berrymed/")

    private fun readSection(section: String): Set<String> {
        if (!listFile.exists()) return emptySet()
        val lines = HashSet<String>()
        var current = ""
        for (line in listFile.readLines()) {
            when {
                line.startsWith("## ") -> current = line.removePrefix("## ").trim()
                line.isBlank() || line.startsWith("#") -> Unit
                current == section -> lines += line
            }
        }
        return lines
    }

    private fun readLists(): Pair<Set<String>, Set<String>> = Pair(readSection("PENDIENTE"), readSection("PERMITIDO"))

    /** Todo lo encontrado va a PENDIENTE, salvo lo que ya estaba en PERMITIDO; los layouts, a su lista. */
    private fun regenerate(found: Set<Found>) {
        val allowed = readSection("PERMITIDO")
        val now = found.map { it.toString() }.toSet()
        val attribute = Regex("""android:(text|hint|contentDescription|title|label|tooltipText)="([^"@?][^"]*)"""")
        val inLayouts = layouts.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }.flatMap { file ->
            attribute.findAll(file.readText()).map { it.groupValues[2] }
                .filter { value -> value.any { it.isLetter() } || Regex("""\d[.,]\d""").containsMatchIn(value) }
                .map { "${file.name}\t$it" }.toList()
        }.toSortedSet()
        listFile.parentFile.mkdirs()
        listFile.writeText(
            buildString {
                appendLine("# Textos escritos en el código (ver HardcodedTextTest). Una línea: archivo<TAB>texto.")
                appendLine("# Regenerar: I18N_REGENERAR=1 ./gradlew testDebugUnitTest --tests '*HardcodedTextTest*'")
                appendLine()
                appendLine("## PENDIENTE")
                (now - allowed).sorted().forEach(::appendLine)
                appendLine()
                appendLine("## PERMITIDO")
                (allowed intersect now).sorted().forEach(::appendLine)
                appendLine()
                appendLine("## LAYOUTS")
                inLayouts.forEach(::appendLine)
            },
        )
    }

    companion object {
        private val logTag = Regex("""^\[[A-ZÁÉÍÓÚÑ0-9 ]+]""")
        private val word = Regex("""\p{L}{3,}""")
        private val twoWords = Regex("""\p{L}{2,}\s+\p{L}{2,}""")
        private val capitalized = Regex("""^[A-ZÁÉÍÓÚÑ][a-záéíóúñ]{3,}[.…:!?]?$""")

        /**
         * ¿Es una frase para una persona? Tiene una palabra y, además, dos
         * palabras seguidas, una letra que no es ASCII (á, ñ, «, …) o es una
         * sola palabra con mayúscula inicial, sola o con un signo al final
         * («Conectado», «Escuchando.», «Encendiendo…»). Quedan afuera los
         * identificadores («berry-usb», «start-monitoring», «HH:mm») y las
         * líneas de log por su etiqueta.
         */
        fun looksLikeProse(literal: String): Boolean {
            if (logTag.containsMatchIn(literal)) return false
            val text = literal.replace("{}", " ").trim()
            if (!word.containsMatchIn(text)) return false
            return twoWords.containsMatchIn(text) || text.any { it.isLetter() && it.code > 127 } || text.any { it in "«»…·¿¡" } ||
                capitalized.matches(text)
        }

        /** Los literales de un fuente Kotlin, sin los comentarios; cada `${…}` o `$nombre` queda como «{}». */
        fun literals(source: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val n = source.length
            while (i < n) {
                val c = source[i]
                when {
                    source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) n else it }
                    source.startsWith("/*", i) -> i = source.indexOf("*/", i).let { if (it < 0) n else it + 2 }
                    source.startsWith("\"\"\"", i) -> {
                        var end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
                        // Un literal crudo termina en las últimas tres comillas: `"""…""""` trae una adentro.
                        while (end + 3 < n && source[end + 3] == '"') end++
                        out += source.substring(i + 3, end).replace('\n', ' ')
                        i = end + 3
                    }
                    c == '\'' -> {
                        var j = i + 1
                        while (j < n && source[j] != '\'') j += if (source[j] == '\\') 2 else 1
                        i = j + 1
                    }
                    c == '"' -> {
                        val text = StringBuilder()
                        var j = i + 1
                        while (j < n && source[j] != '"') {
                            when {
                                source[j] == '\\' -> {
                                    text.append(source, j, minOf(j + 2, n))
                                    j += 2
                                }
                                source.startsWith("\${", j) -> {
                                    // Una plantilla puede traer literales adentro: se leen aparte.
                                    var depth = 1
                                    var k = j + 2
                                    while (k < n && depth > 0) {
                                        when (source[k]) {
                                            '{' -> depth++
                                            '}' -> depth--
                                            '"' -> {
                                                var m = k + 1
                                                while (m < n && source[m] != '"') m += if (source[m] == '\\') 2 else 1
                                                k = m
                                            }
                                        }
                                        k++
                                    }
                                    out += literals(source.substring(j + 2, k - 1))
                                    text.append("{}")
                                    j = k
                                }
                                source[j] == '$' && j + 1 < n && (source[j + 1].isLetter() || source[j + 1] == '_') -> {
                                    var k = j + 1
                                    while (k < n && (source[k].isLetterOrDigit() || source[k] == '_')) k++
                                    text.append("{}")
                                    j = k
                                }
                                else -> {
                                    text.append(source[j])
                                    j++
                                }
                            }
                        }
                        out += text.toString()
                        i = j + 1
                    }
                    else -> i++
                }
            }
            return out
        }
    }
}
