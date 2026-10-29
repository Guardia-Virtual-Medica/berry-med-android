// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Lo que hace falta para entrar a la sala del encuentro. El token nunca va al log. */
data class TeleCredentials(val serverUrl: String, val token: String) {
    /** El token sin el JWT entero, para comparar si es el mismo. */
    override fun toString(): String = "TeleCredentials(serverUrl=$serverUrl, token=…)"
}

/** Lo que se lee del JWT sin verificar la firma: sólo para mostrar y para cruzar con las órdenes. */
data class TokenInfo(val room: String?, val expiresAtMillis: Long?)

/**
 * Lo que Berry Med lee de las direcciones que carga el kiosco, como hacía la
 * app del proveedor: al crear el encuentro el kiosco navega a
 * `/{slug}/waiting-room?serverUrl=…&token=…` (o `/triage-manual?…`,
 * `/cmd/sessionResume?…`), y alguna versión vieja de la API mandaba
 * `koplak://auth?serverUrl=…&token=…`. Sin Android, para probarlo solo.
 */
object KioskUrls {
    const val PROVIDER_SCHEME = "koplak"

    /** Las pantallas del kiosco sin encuentro: al volver a ellas, Berry Med sale de la sala. */
    private val HOME_PAGES = setOf("login", "setup")

    /** `serverUrl` y `token` de [url], o null si no trae los dos. */
    fun credentials(url: String): TeleCredentials? {
        val query = query(url) ?: return null
        val server = query["serverUrl"]?.trim().orEmpty()
        val token = query["token"]?.trim().orEmpty()
        if (server.isEmpty() || token.isEmpty()) return null
        return TeleCredentials(serverFor(server), token)
    }

    /** Si [url] es la del proveedor (`koplak://…`), que no se navega: sólo se le sacan las credenciales. */
    fun isProviderScheme(url: String): Boolean = url.trim().lowercase().startsWith("$PROVIDER_SCHEME:")

    /**
     * La API manda el subdominio de LiveKit Cloud (`services.livekit.subdomain`).
     * Si ya es una dirección, va tal cual; si no, `wss://{subdominio}.livekit.cloud`.
     */
    fun serverFor(value: String): String {
        val clean = value.trim()
        val lower = clean.lowercase()
        if (listOf("ws://", "wss://", "http://", "https://").any { lower.startsWith(it) }) return clean
        return "wss://${clean.trim('/')}.livekit.cloud"
    }

    /**
     * Si [url] es una pantalla del kiosco sin encuentro: el inicio de la
     * organización (`/{slug}`), el login o el setup, en el mismo origen que
     * [kioskUrl]. Las rutas salen de `gvm.mt.totem` (`src/routes/$slug`).
     */
    fun isHome(url: String, kioskUrl: String): Boolean {
        val page = parse(url) ?: return false
        val kiosk = parse(kioskUrl) ?: return false
        if (!sameOrigin(page, kiosk)) return false
        if (credentials(url) != null) return false
        val segments = page.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return when (segments.size) {
            0, 1 -> true
            2 -> segments[1] in HOME_PAGES
            else -> false
        }
    }

    /** El origen de [url] ("https://host:puerto"), o null. Lo usa el WebView para conceder cámara y micrófono. */
    fun origin(url: String): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }

    /**
     * La sala y el vencimiento del token, del cuerpo del JWT sin verificar la
     * firma (eso lo hace LiveKit). Null en cada campo que no se pueda leer.
     */
    fun tokenInfo(token: String): TokenInfo {
        return try {
            val payload = token.split('.').getOrNull(1) ?: return TokenInfo(null, null)
            val json = JsonParser.parseString(String(Base64.getUrlDecoder().decode(payload.trimEnd('=')))).asJsonObject
            val exp = json.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong
            val room = json.getAsJsonObject("video")?.get("room")?.takeIf { it.isJsonPrimitive }?.asString
            TokenInfo(room, exp?.let { it * 1_000L })
        } catch (_: Exception) {
            TokenInfo(null, null)
        }
    }

    /**
     * [text] sin tokens: los parámetros `token=…`/`access_token=…` y cualquier
     * cosa con forma de JWT. Para lo que el kiosco escribe en su consola.
     */
    fun redact(text: String): String = text
        .replace(Regex("(access_token|token)=[^&\\s\"']+"), "$1=…")
        .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "…")

    /** El encuentro de una sala `encounter-{id}`, o null si la sala no es de un encuentro. */
    fun encounterOf(room: String?): String? = room?.takeIf { it.startsWith(ROOM_PREFIX) }?.removePrefix(ROOM_PREFIX)

    private const val ROOM_PREFIX = "encounter-"

    private fun query(url: String): Map<String, String>? {
        val raw = url.substringAfter('?', "").substringBefore('#')
        if (raw.isEmpty()) return null
        return raw.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            if (name.isEmpty() || !pair.contains('=')) return@mapNotNull null
            decode(name) to decode(pair.substringAfter('='))
        }.toMap()
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
