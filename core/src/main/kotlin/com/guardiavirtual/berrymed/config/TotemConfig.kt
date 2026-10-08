package com.guardiavirtual.berrymed.config

import com.guardiavirtual.berrymed.protocol.Pm6750Protocol

data class TotemConfig(
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
    val maxSessionMinutes: Int = 30,
    val ecgGain: String = "x1",
    val ecgMode: String = "monitor",
    val respGain: String = "x1",
    val nibpMode: String = "adulto",
    val nibpTargetPressureMmhg: Int? = null,
    val thermometerEnabled: Boolean = true,
) {
    fun metricsUrl(): String = buildMetricsUrl(apiBaseUrl, totemId, apiEndpoint)

    fun deviceConfig() = Pm6750Protocol.DeviceConfig(
        ecgGain = ecgGain,
        ecgMode = ecgMode,
        respGain = respGain,
        nibpMode = nibpMode,
        nibpTargetPressureMmhg = nibpTargetPressureMmhg,
    )

    fun isConfigured(): Boolean =
        apiBaseUrl.isNotBlank() &&
            totemId.isNotBlank() &&
            apiUsername.isNotBlank() &&
            apiPassword.isNotBlank() &&
            pusherKey.isNotBlank() &&
            publicChannel.isNotBlank()

    companion object {
        fun buildMetricsUrl(base: String, totemId: String, endpoint: String = "metrics"): String {
            val b = base.trim().trimEnd('/')
            val id = totemId.trim().trim('/')
            val ep = endpoint.trim().trim('/')
            return "$b/$id/$ep"
        }
    }
}
