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
 * POST de métricas. Hasta tres intentos ante un error de red (con medio segundo
 * entre intentos) o un 5xx. Un 4xx no se reintenta: un 404 ("no hay toma
 * abierta") no cambia por insistir. Después el paquete se descarta y el
 * siguiente lleva datos nuevos.
 */
class MetricsPoster {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun post(url: String, username: String, password: String, json: String): PostOutcome {
        val builder = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON))
        if (username.isNotBlank()) builder.header("Authorization", Credentials.basic(username, password))
        val request = builder.build()
        var last: PostOutcome = PostOutcome.Failed("sin intento")
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) return PostOutcome.Ok
                    if (response.code in 400..499) return PostOutcome.Http(response.code)
                    last = PostOutcome.Http(response.code)
                }
            } catch (error: Exception) {
                last = PostOutcome.Failed(error.message ?: error.javaClass.simpleName)
                if (attempt < 2) Thread.sleep(500)
            }
        }
        return last
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
