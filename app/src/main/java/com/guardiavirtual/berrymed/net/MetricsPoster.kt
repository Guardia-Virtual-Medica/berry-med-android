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

/** POST de métricas. Tres intentos; un código distinto de 200 descarta el paquete. */
class MetricsPoster {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun post(url: String, username: String, password: String, json: String): PostOutcome {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, password))
            .post(json.toRequestBody(JSON))
            .build()
        var last: PostOutcome = PostOutcome.Failed("sin intento")
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) return PostOutcome.Ok
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
