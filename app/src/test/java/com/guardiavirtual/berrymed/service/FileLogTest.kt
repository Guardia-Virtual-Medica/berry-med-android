// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileLogTest {
    @Test
    fun everythingButDataGoesToTheFile() {
        assertTrue(FileLog.keep("[TELE] Publicando el otoscopio"))
        assertTrue(FileLog.keep("[YKUSH] Puertos en reposo"))
        assertFalse(FileLog.keep("[DATA] vitalSigns=…"))
        assertFalse(FileLog.keep("[DATA] Error en el envío: timeout"))
    }

    @Test
    fun itRotatesOnlyWhenTheLineDoesNotFit() {
        assertFalse(FileLog.mustRotate(size = 0, adding = 2_000_000, limit = 1_000_000))
        assertFalse(FileLog.mustRotate(size = 999_900, adding = 100, limit = 1_000_000))
        assertTrue(FileLog.mustRotate(size = 999_901, adding = 100, limit = 1_000_000))
    }

    @Test
    fun eachLineHasDateAndTime() {
        val line = FileLog.line(0L, "[TELE] hola")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[TELE] hola\n""").matches(line), line)
    }

    @Test
    fun itWritesAndKeepsThePreviousFileWhenItRotates() {
        val dir = Files.createTempDirectory("berry-log").toFile()
        try {
            val log = FileLog(File(dir, "logs"), limitBytes = 60, executor = { it.run() }, clock = { 0L })
            log.add("[TELE] primera")
            log.add("[DATA] no va")
            log.add("[TELE] segunda")
            val current = File(dir, "logs/${FileLog.NAME}")
            val old = File(dir, "logs/${FileLog.OLD_NAME}")
            assertTrue(old.readText().contains("primera"))
            assertEquals(1, current.readLines().size)
            assertTrue(current.readText().contains("segunda"))
            assertFalse(current.readText().contains("no va"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
