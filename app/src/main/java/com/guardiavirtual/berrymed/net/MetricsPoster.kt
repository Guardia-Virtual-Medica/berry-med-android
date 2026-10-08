package com.guardiavirtual.berrymed.net

import android.util.Log
import com.guardiavirtual.berrymed.config.TotemConfig
import com.guardiavirtual.berrymed.protocol.MetricsData
import com.guardiavirtual.berrymed.protocol.PayloadValidator
import com.guardiavirtual.berrymed.usb.ThermometerReader
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MetricsPoster(private val client: OkHttpClient = defaultClient()) {

    fun post(config: TotemConfig, data: MetricsData, therm: ThermometerReader?): Boolean {
        if (!PayloadValidator.isValid(data)) return false
        applyThermometer(data, therm)
        val payload = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("data", data.toJsonObject())
        val body = payload.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(config.metricsUrl())
            .header("Authorization", Credentials.basic(config.apiUsername, config.apiPassword))
            .post(body)
            .build()
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return true
                    Log.w(TAG, "API status ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "POST attempt ${attempt + 1}: ${e.message}")
                if (attempt < 2) Thread.sleep(500)
            }
        }
        return false
    }

    private fun applyThermometer(data: MetricsData, therm: ThermometerReader?) {
        val latest = therm?.getLatest() ?: return
        var celsius = latest.value
        if (latest.unit == "F") {
            celsius = (latest.value - 32) * 5 / 9
        }
        val formatted = when {
            celsius == 0.0 -> "-"
            else -> ((celsius * 10).toInt() / 10.0).toString()
        }
        data.vitalSigns.temperature = formatted
    }

    companion object {
        private const val TAG = "MetricsPoster"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
