// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * El log propio de Berry Med, en `files/logs/berry.log`: todo lo que va a la
 * consola menos `[DATA]`, con fecha y hora. El logcat de la tablet se llena
 * con una consulta y pierde el principio; este archivo no. Al pasar
 * [limitBytes] se rota una vez: el actual pasa a `berry.1.log` (pisando el
 * anterior) y se empieza otro. Se escribe en un hilo propio: quien llama no
 * espera al disco. Un error de escritura no sale de acá.
 *
 * Se saca con `adb exec-out run-as com.guardiavirtual.berrymed cat files/logs/berry.log`.
 */
class FileLog(
    private val dir: File,
    private val limitBytes: Long = LIMIT_BYTES,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-log").apply { isDaemon = true }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun add(message: String) {
        if (!keep(message)) return
        val bytes = line(clock(), message).toByteArray(Charsets.UTF_8)
        executor.execute { write(bytes) }
    }

    private fun write(bytes: ByteArray) {
        try {
            dir.mkdirs()
            val file = File(dir, NAME)
            if (mustRotate(file.length(), bytes.size, limitBytes)) {
                val old = File(dir, OLD_NAME)
                old.delete()
                file.renameTo(old)
            }
            FileOutputStream(file, true).use { it.write(bytes) }
        } catch (_: Exception) {
            // Sin disco no hay log en archivo: el logcat y la consola siguen.
        }
    }

    companion object {
        const val NAME = "berry.log"
        const val OLD_NAME = "berry.1.log"
        const val LIMIT_BYTES = 1_000_000L

        /** Lo que va al archivo: todo menos `[DATA]`, que es una línea por segundo. */
        fun keep(message: String): Boolean = !message.startsWith("[DATA]")

        /** Si sumar [adding] bytes a un archivo de [size] lo pasa de [limit]. Un archivo vacío no se rota nunca. */
        fun mustRotate(size: Long, adding: Int, limit: Long): Boolean = size > 0 && size + adding > limit

        /** "2026-11-15 19:48:11.042 [TELE] …" y el fin de línea. */
        fun line(atMillis: Long, message: String): String =
            "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(atMillis))} $message\n"
    }
}
