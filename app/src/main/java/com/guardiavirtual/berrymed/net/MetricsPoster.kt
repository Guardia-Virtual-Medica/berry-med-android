// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

sealed class PostOutcome {
    data object Ok : PostOutcome()
    data class Http(val code: Int) : PostOutcome()
    data class Failed(val message: String) : PostOutcome()
}

/**
 * POST de métricas. Cualquier 2xx es éxito: antes sólo 200, y un 201 o 204 se
 * reintentaba y le llegaba al servidor tres veces. Ante un error de red o un
 * 5xx se espera medio segundo y se reintenta una sola vez. Un 4xx no se
 * reintenta: un 404 ("no hay toma abierta") no cambia por insistir. Después
 * el paquete se descarta y el siguiente lleva datos nuevos.
 *
 * Cada intento tiene [callTimeoutMs] en total (conectar, mandar y leer): un
 * paquete no tarda más de unos 11 s. Con los tiempos de antes podía pasar el
 * minuto, la cola de envío se llenaba en segundos y un segundo de ECG que llega
 * tan tarde ya no le sirve al médico, que mira en vivo.
 */
class MetricsPoster(
    callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val retryPauseMs: Long = RETRY_PAUSE_MS,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    fun post(url: String, username: String, password: String, json: String): PostOutcome {
        val builder = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON))
        if (username.isNotBlank()) builder.header("Authorization", Credentials.basic(username, password))
        val request = builder.build()
        var last: PostOutcome = PostOutcome.Failed("sin intento")
        for (attempt in 1..ATTEMPTS) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return PostOutcome.Ok
                    // Sólo un 5xx puede cambiar insistiendo; un 4xx (o un 3xx que no se siguió) no.
                    if (response.code !in 500..599) return PostOutcome.Http(response.code)
                    last = PostOutcome.Http(response.code)
                }
            } catch (error: Exception) {
                last = PostOutcome.Failed(error.message ?: error.javaClass.simpleName)
            }
            if (attempt < ATTEMPTS) Thread.sleep(retryPauseMs)
        }
        return last
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val ATTEMPTS = 2
        const val CALL_TIMEOUT_MS = 5_000L
        const val RETRY_PAUSE_MS = 500L
    }
}
