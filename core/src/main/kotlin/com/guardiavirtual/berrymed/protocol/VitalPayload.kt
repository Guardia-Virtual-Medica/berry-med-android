package com.guardiavirtual.berrymed.protocol

import org.json.JSONArray
import org.json.JSONObject

data class EcgPeak(val sample: Int, val ms: Double)

data class EcgInfo(
    val leadOff: Boolean?,
    val weakSignal: Boolean?,
    val gain: String?,
    val mode: String?,
    val st: Double?,
    val hrv: String?,
)

data class VitalSigns(
    var heartRate: String = "- -",
    var respRate: String = "- -",
    var spo2Pulse: String = NO_VALUE,
    var temperature: String = "- -",
    var nibp: String = NO_VALUE,
) {
    companion object {
        const val NO_VALUE = "- - /- -"
    }
}

data class MetricsData(
    val spo2: List<Int>,
    val ecg: Map<String, List<Int>>,
    val ecgPeaks: List<EcgPeak>,
    val ecgInfo: EcgInfo,
    val resp: List<Int>,
    val vitalSigns: VitalSigns,
) {
    fun toJsonObject(): JSONObject {
        val ecgObj = JSONObject()
        ecg.forEach { (lead, values) ->
            ecgObj.put(lead, JSONArray(values))
        }
        val peaks = JSONArray()
        ecgPeaks.forEach { p ->
            peaks.put(JSONObject().put("sample", p.sample).put("ms", p.ms))
        }
        val info = JSONObject()
        ecgInfo.leadOff?.let { info.put("leadOff", it) }
        ecgInfo.weakSignal?.let { info.put("weakSignal", it) }
        ecgInfo.gain?.let { info.put("gain", it) }
        ecgInfo.mode?.let { info.put("mode", it) }
        ecgInfo.st?.let { info.put("st", it) }
        ecgInfo.hrv?.let { info.put("hrv", it) }

        val vitals = JSONObject()
        vitals.put("heartRate", vitalSigns.heartRate)
        vitals.put("respRate", vitalSigns.respRate)
        vitals.put("spo2Pulse", vitalSigns.spo2Pulse)
        vitals.put("temperature", vitalSigns.temperature)
        vitals.put("nibp", vitalSigns.nibp)

        return JSONObject()
            .put("spo2", JSONArray(spo2))
            .put("ecg", ecgObj)
            .put("ecgPeaks", peaks)
            .put("ecgInfo", info)
            .put("resp", JSONArray(resp))
            .put("vitalSigns", vitals)
    }
}

object PayloadValidator {
    private val defaultValues = setOf("-", "- -", VitalSigns.NO_VALUE)

    fun isValid(data: MetricsData): Boolean {
        val vitals = data.vitalSigns
        if (vitals.temperature !in defaultValues) return true
        val allVitalsEmpty = listOf(
            vitals.heartRate,
            vitals.respRate,
            vitals.spo2Pulse,
            vitals.temperature,
            vitals.nibp,
        ).all { it in defaultValues }

        val spo2Empty = data.spo2.all { it == 0 }
        val ecgEmpty = data.ecg.values.none { it.isNotEmpty() }
        val respEmpty = data.resp.isEmpty()
        return !(allVitalsEmpty && spo2Empty && ecgEmpty && respEmpty)
    }
}
