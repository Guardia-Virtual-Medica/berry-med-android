// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import com.guardiavirtual.berrymed.protocol.DeviceTuning

data class BerryConfig(
    val environment: String = Environment.Prod.key,
    val apiBaseUrl: String = Environment.Prod.apiBaseUrl,
    val totemId: String = "",
    val apiEndpoint: String = "metrics",
    val apiUsername: String = "",
    val apiPassword: String = "",
    val pusherKey: String = Environment.Prod.pusherKey,
    val pusherCluster: String = Environment.Prod.pusherCluster,
    val publicChannel: String = "",
    val startEventName: String = "start-monitoring",
    val stopEventName: String = "stop-monitoring",
    val ecgGain: String = "x1",
    val ecgMode: String = "monitor",
    val respGain: String = "x1",
    val nibpMode: String = "adulto",
    val nibpTargetPressureMmhg: String = "",
    val maxSessionMinutes: Int = 30,
    /** En qué puerto del hub YKUSH3 va cada equipo (1 a 3). */
    val ykushOtoscopePort: Int = 1,
    val ykushStethoscopePort: Int = 2,
    val ykushHeadsetPort: Int = 3,
) {
    /** Qué equipo va en cada puerto del YKUSH3, para la pantalla. Un puerto sin equipo queda "libre". */
    fun ykushPortRoles(): List<String> = (1..YKUSH_PORTS).map { port ->
        listOfNotNull(
            "otoscopio".takeIf { ykushOtoscopePort == port },
            "estetoscopio".takeIf { ykushStethoscopePort == port },
            "auricular".takeIf { ykushHeadsetPort == port },
        ).joinToString(" y ").ifBlank { "libre" }
    }

    fun tuning(): DeviceTuning = DeviceTuning(
        ecgGain = ecgGain,
        ecgMode = ecgMode,
        respGain = respGain,
        nibpMode = nibpMode,
        nibpTargetPressureMmhg = nibpTargetPressureMmhg,
    )

    fun metricsUrl(): String? {
        if (apiBaseUrl.isBlank() || totemId.isBlank() || !hasWebScheme(apiBaseUrl)) return null
        return buildMetricsUrl(apiBaseUrl, totemId, apiEndpoint)
    }

    fun pusherReady(): Boolean = pusherKey.isNotBlank() && publicChannel.isNotBlank() && channelIsPublic()

    /** La librería tira una excepción al suscribirse a un canal privado o de presencia sin autenticación. */
    fun channelIsPublic(): Boolean =
        !publicChannel.startsWith("private-") && !publicChannel.startsWith("presence-")

    /** OnDoctor no pide usuario ni contraseña: si están vacíos, el POST va sin autenticación. */
    fun postReady(): Boolean = metricsUrl() != null

    fun missingFields(): List<String> {
        val missing = ArrayList<String>()
        if (apiBaseUrl.isBlank()) missing += "URL de la API"
        if (apiBaseUrl.isNotBlank() && !hasWebScheme(apiBaseUrl)) missing += "URL de la API que empiece con https://"
        if (totemId.isBlank()) missing += "tótem"
        if (pusherKey.isBlank()) missing += "clave de Pusher"
        if (publicChannel.isBlank()) missing += "canal"
        if (!channelIsPublic()) missing += "un canal público (sin private- ni presence-)"
        return missing
    }
}

const val YKUSH_PORTS = 3

/** Un número de puerto del YKUSH3 escrito a mano: fuera de 1 a 3 vuelve al valor por defecto. */
fun ykushPortOrDefault(text: String?, default: Int): Int {
    val port = text?.trim()?.toIntOrNull() ?: return default
    return if (port in 1..YKUSH_PORTS) port else default
}

/** Sin http:// o https:// el POST no puede armar la dirección y no sale nunca. */
fun hasWebScheme(url: String): Boolean {
    val clean = url.trim().lowercase()
    return clean.startsWith("https://") || clean.startsWith("http://")
}

fun buildMetricsUrl(base: String, totemId: String, endpoint: String = "metrics"): String {
    fun clean(value: String) = value.trim().trim('/')
    val path = clean(endpoint).ifBlank { "metrics" }
    return "${clean(base)}/${clean(totemId)}/$path"
}
