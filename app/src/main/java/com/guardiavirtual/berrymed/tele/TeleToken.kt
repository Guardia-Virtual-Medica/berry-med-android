// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.guardiavirtual.berrymed.net.MetricsPoster
import com.guardiavirtual.berrymed.net.webhookClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Lo que hace falta para entrar a la sala del encuentro. */
data class TeleCredentials(val serverUrl: String, val room: String, val token: String)

sealed class TokenOutcome {
    data class Ok(val credentials: TeleCredentials) : TokenOutcome()
    data class Failed(val message: String) : TokenOutcome()
}

/**
 * Pide a la API el token para publicar los instrumentos en la sala del
 * encuentro (`POST {URL base}/{tótem}/livekit-token`). Mismo cliente, mismos
 * tiempos y misma regla de reintento que [MetricsPoster]: un error de red o
 * un 5xx se reintenta una vez; un 4xx (404, 409, 422, 429) no. Contrato de
 * gvm.mt.api PR #29.
 */
class TeleTokenClient(
    callTimeoutMs: Long = MetricsPoster.CALL_TIMEOUT_MS,
    private val retryPauseMs: Long = MetricsPoster.RETRY_PAUSE_MS,
) {
    private val client: OkHttpClient = webhookClient(callTimeoutMs)

    fun fetch(url: String, encounterId: String): TokenOutcome {
        val body = JsonObject().apply { addProperty("encounter_external_id", encounterId) }.toString()
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
        var last: TokenOutcome = TokenOutcome.Failed("sin intento")
        for (attempt in 1..MetricsPoster.ATTEMPTS) {
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return parse(text)?.let { TokenOutcome.Ok(it) }
                            ?: TokenOutcome.Failed("La API respondió ${response.code} sin server_url, room o token")
                    }
                    val outcome = TokenOutcome.Failed(describe(response.code, errorCode(text)))
                    if (response.code !in 500..599) return outcome
                    last = outcome
                }
            } catch (error: Exception) {
                last = TokenOutcome.Failed("No se pudo pedir el token: ${error.message ?: error.javaClass.simpleName}")
            }
            if (attempt < MetricsPoster.ATTEMPTS) Thread.sleep(retryPauseMs)
        }
        return last
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** `{server_url, room, token}`, o null si falta alguno. */
        fun parse(text: String): TeleCredentials? = try {
            val json = JsonParser.parseString(text).asJsonObject
            fun field(name: String): String? =
                json.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }
            val server = field("server_url")
            val room = field("room")
            val token = field("token")
            if (server == null || room == null || token == null) null else TeleCredentials(server, room, token)
        } catch (_: Exception) {
            null
        }

        /**
         * El motivo para el log y Testing. Ninguno de éstos se reintenta solo
         * (sólo un 5xx o un error de red, una vez): el 409 y el 422 no cambian
         * por insistir y el 429 es el límite de 30 pedidos por minuto.
         */
        fun describe(code: Int, errorCode: String? = null): String {
            val detail = errorCode?.let { " · $it" }.orEmpty()
            return when (code) {
                404 -> "La API no conoce este tótem o está deshabilitado (404)"
                409 -> "El encuentro no es de este tótem, no está en curso o no transmite instrumentos (409$detail)"
                422 -> "La API rechazó el identificador del encuentro (422)"
                429 -> "Demasiados pedidos de token: la API limita a 30 por minuto (429)"
                else -> "La API respondió $code al pedir el token"
            }
        }

        /** El campo `code` del cuerpo de un error, si viene. */
        fun errorCode(text: String): String? = try {
            JsonParser.parseString(text).asJsonObject.get("code")
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
