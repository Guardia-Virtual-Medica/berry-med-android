// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import com.guardiavirtual.berrymed.protocol.DeviceTuning

data class BerryConfig(
    val apiBaseUrl: String = "",
    val totemId: String = "",
    val apiEndpoint: String = "metrics",
    val apiUsername: String = "",
    val apiPassword: String = "",
    val pusherKey: String = "",
    val pusherCluster: String = "us2",
    val publicChannel: String = "",
    val startEventName: String = "start-monitoring",
    val stopEventName: String = "stop-monitoring",
    val ecgGain: String = "x1",
    val ecgMode: String = "monitor",
    val respGain: String = "x1",
    val nibpMode: String = "adulto",
    val nibpTargetPressureMmhg: String = "",
    val maxSessionMinutes: Int = 30,
) {
    fun tuning(): DeviceTuning = DeviceTuning(
        ecgGain = ecgGain,
        ecgMode = ecgMode,
        respGain = respGain,
        nibpMode = nibpMode,
        nibpTargetPressureMmhg = nibpTargetPressureMmhg,
    )

    fun metricsUrl(): String? {
        if (apiBaseUrl.isBlank() || totemId.isBlank()) return null
        return buildMetricsUrl(apiBaseUrl, totemId, apiEndpoint)
    }

    fun pusherReady(): Boolean = pusherKey.isNotBlank() && publicChannel.isNotBlank() && channelIsPublic()

    /** La librería tira una excepción al suscribirse a un canal privado o de presencia sin autenticación. */
    fun channelIsPublic(): Boolean =
        !publicChannel.startsWith("private-") && !publicChannel.startsWith("presence-")

    fun postReady(): Boolean =
        metricsUrl() != null && apiUsername.isNotBlank() && apiPassword.isNotBlank()

    fun missingFields(): List<String> {
        val missing = ArrayList<String>()
        if (apiBaseUrl.isBlank()) missing += "URL de la API"
        if (totemId.isBlank()) missing += "tótem"
        if (apiUsername.isBlank()) missing += "usuario"
        if (apiPassword.isBlank()) missing += "contraseña"
        if (pusherKey.isBlank()) missing += "clave de Pusher"
        if (publicChannel.isBlank()) missing += "canal"
        if (!channelIsPublic()) missing += "un canal público (sin private- ni presence-)"
        return missing
    }
}

fun buildMetricsUrl(base: String, totemId: String, endpoint: String = "metrics"): String {
    fun clean(value: String) = value.trim().trim('/')
    val path = clean(endpoint).ifBlank { "metrics" }
    return "${clean(base)}/${clean(totemId)}/$path"
}
