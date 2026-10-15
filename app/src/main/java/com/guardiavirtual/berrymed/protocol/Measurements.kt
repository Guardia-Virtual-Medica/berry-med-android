// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.protocol

import java.util.Locale

const val NO_PAIR = "- - /- -"

data class VitalSigns(
    var heartRate: String = "- -",
    var nibp: String = NO_PAIR,
    var spo2Pulse: String = NO_PAIR,
    var temperature: String = "- -",
    var respRate: String = "- -",
)

data class EcgInfo(
    var leadOff: Boolean? = null,
    var weakSignal: Boolean? = null,
    var gain: String? = null,
    var mode: String? = null,
    var st: Double? = null,
    var hrv: String? = null,
)

data class EcgPeak(val sample: Int, val ms: Double)

data class SampleWindow(
    val spo2: List<Int>,
    val ecg: Map<String, List<Int>>,
    val ecgPeaks: List<EcgPeak>,
    val ecgInfo: EcgInfo,
    val resp: List<Int>,
    val vitalSigns: VitalSigns,
)

data class ThermoReading(val value: Double, val unit: String)

private val EMPTY_SENTINELS = setOf("-", "- -", NO_PAIR)

fun isValidSample(window: SampleWindow): Boolean {
    val vitals = window.vitalSigns
    if (vitals.temperature !in EMPTY_SENTINELS) return true
    val allEmpty = listOf(vitals.heartRate, vitals.nibp, vitals.spo2Pulse, vitals.temperature, vitals.respRate)
        .all { it in EMPTY_SENTINELS }
    val spo2Empty = window.spo2.all { it == 0 }
    val ecgEmpty = window.ecg.values.all { it.isEmpty() }
    val respEmpty = window.resp.isEmpty()
    return !(allEmpty && spo2Empty && ecgEmpty && respEmpty)
}

/** Un segundo de ECG por derivación. Un paquete así pesa unos 7 KB. */
const val MAX_ECG_SAMPLES_PER_PACKET = 250

/**
 * Parte una ventana en tramos de a lo sumo [maxEcgSamples] muestras de ECG por
 * derivación. El servidor reenvía cada paquete por Pusher, que no acepta más de
 * 10 KB: con las 7 derivaciones completas, dos segundos ya se pasan. Las ondas
 * de SpO₂ y respiración se reparten en la misma proporción, los latidos quedan
 * en el tramo donde caen (con el índice corrido) y los signos van en todos.
 */
fun splitWindow(window: SampleWindow, maxEcgSamples: Int = MAX_ECG_SAMPLES_PER_PACKET): List<SampleWindow> {
    val ecgLength = window.ecg.values.maxOfOrNull { it.size } ?: 0
    if (ecgLength <= maxEcgSamples) return listOf(window)
    val parts = (ecgLength + maxEcgSamples - 1) / maxEcgSamples
    return (0 until parts).map { part ->
        val from = part * maxEcgSamples
        val to = minOf(from + maxEcgSamples, ecgLength)
        SampleWindow(
            spo2 = proportionalSlice(window.spo2, part, parts),
            ecg = window.ecg.mapValues { (_, samples) -> samples.subList(minOf(from, samples.size), minOf(to, samples.size)) },
            ecgPeaks = window.ecgPeaks
                .filter { it.sample in from until to }
                .map { peak ->
                    val sample = peak.sample - from
                    EcgPeak(sample, java.lang.Math.round(sample * 1000.0 / Pm6750Protocol.ECG_SAMPLE_RATE_HZ * 10.0) / 10.0)
                },
            ecgInfo = window.ecgInfo.copy(),
            resp = proportionalSlice(window.resp, part, parts),
            vitalSigns = window.vitalSigns.copy(),
        )
    }
}

private fun proportionalSlice(values: List<Int>, part: Int, parts: Int): List<Int> =
    values.subList(values.size * part / parts, values.size * (part + 1) / parts)

fun parseThermometerLine(line: String): ThermoReading? {
    // El valor puede venir como número o entre comillas; el monitor de Windows acepta las dos.
    val value = Regex(""""valor_temperatura_ir"\s*:\s*("?)(-?[0-9]+(?:\.[0-9]+)?)\1\s*[,}]""")
        .find(line)?.groupValues?.get(2)?.toDoubleOrNull() ?: return null
    val unit = Regex(""""unit"\s*:\s*"([^"]+)"""")
        .find(line)?.groupValues?.get(1)?.uppercase(Locale.US) ?: return null
    return ThermoReading(value, unit)
}

fun applyThermometer(vitals: VitalSigns, reading: ThermoReading?) {
    if (reading == null) return
    val celsius = if (reading.unit == "F") (reading.value - 32.0) * 5.0 / 9.0 else reading.value
    vitals.temperature = formatCelsius(celsius)
}

fun formatCelsius(value: Double): String {
    if (value == 0.0) return "-"
    val rounded = java.lang.Math.round(value * 10.0) / 10.0
    if (rounded == 0.0) return "-"
    return String.format(Locale.US, "%.1f", rounded)
}

fun sessionExpired(active: Boolean, startedAtNano: Long, nowNano: Long, maxMinutes: Int): Boolean {
    if (!active || maxMinutes <= 0 || startedAtNano == 0L) return false
    return nowNano - startedAtNano >= maxMinutes.toLong() * 60L * 1_000_000_000L
}

fun metricsEnvelope(timestampMillis: Long, window: SampleWindow): String {
    val json = StringBuilder(512)
    json.append("{\"timestamp\":").append(timestampMillis).append(",\"data\":{")
    json.append("\"spo2\":").append(intList(window.spo2)).append(',')
    json.append("\"ecg\":{")
    Pm6750Protocol.LEADS.forEachIndexed { index, lead ->
        if (index > 0) json.append(',')
        json.append(quote(lead)).append(':').append(intList(window.ecg[lead].orEmpty()))
    }
    json.append("},\"ecgPeaks\":[")
    window.ecgPeaks.forEachIndexed { index, peak ->
        if (index > 0) json.append(',')
        json.append("{\"sample\":").append(peak.sample)
            .append(",\"ms\":").append(String.format(Locale.US, "%.1f", peak.ms)).append('}')
    }
    json.append("],\"ecgInfo\":{")
    json.append("\"leadOff\":").append(jsonBool(window.ecgInfo.leadOff)).append(',')
    json.append("\"weakSignal\":").append(jsonBool(window.ecgInfo.weakSignal)).append(',')
    json.append("\"gain\":").append(jsonString(window.ecgInfo.gain)).append(',')
    json.append("\"mode\":").append(jsonString(window.ecgInfo.mode)).append(',')
    json.append("\"st\":").append(jsonSt(window.ecgInfo.st)).append(',')
    json.append("\"hrv\":").append(jsonString(window.ecgInfo.hrv))
    json.append("},\"resp\":").append(intList(window.resp)).append(',')
    val v = window.vitalSigns
    json.append("\"vitalSigns\":{")
    json.append("\"heartRate\":").append(quote(v.heartRate)).append(',')
    json.append("\"nibp\":").append(quote(v.nibp)).append(',')
    json.append("\"spo2Pulse\":").append(quote(v.spo2Pulse)).append(',')
    json.append("\"temperature\":").append(quote(v.temperature)).append(',')
    json.append("\"respRate\":").append(quote(v.respRate))
    json.append("}}}")
    return json.toString()
}

fun summarize(window: SampleWindow): String {
    val leads = Pm6750Protocol.LEADS.joinToString(",") { lead ->
        "$lead=${window.ecg[lead]?.size ?: 0}"
    }
    return "vitalSigns=${window.vitalSigns} waves=spo2:${window.spo2.size},resp:${window.resp.size} " +
        "ecg=$leads peaks=${window.ecgPeaks.size}"
}

private fun intList(values: List<Int>): String = values.joinToString(prefix = "[", postfix = "]")

private fun quote(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

private fun jsonString(value: String?): String = if (value == null) "null" else quote(value)

private fun jsonBool(value: Boolean?): String = when (value) {
    null -> "null"
    true -> "true"
    false -> "false"
}

private fun jsonSt(value: Double?): String {
    if (value == null) return "null"
    return String.format(Locale.US, "%.2f", java.lang.Math.round(value * 100.0) / 100.0)
}
