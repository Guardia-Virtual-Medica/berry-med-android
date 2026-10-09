// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.protocol

/**
 * Arma el objeto que se postea, a partir de las tramas `55 AA` del PM6750.
 * El comportamiento sigue a `src/data_parser.py`: las ondas se entregan y se
 * vacían, los signos vitales quedan, y una presión en cero no pisa la anterior.
 */
class BmDataParser {
    private val lock = Any()
    private val raw = ArrayList<Int>()
    private var spo2 = ArrayList<Int>()
    private var ecg = emptyLeads()
    private var peaks = ArrayList<EcgPeak>()
    private var ecgInfo = EcgInfo()
    private var resp = ArrayList<Int>()
    private var vitals = VitalSigns()

    var onNibp: ((status: Int, cuffMmhg: Int, sys: Int, mean: Int, dia: Int) -> Unit)? = null

    fun addData(data: ByteArray) {
        if (data.isEmpty()) return
        val events = ArrayList<NibpEvent>()
        synchronized(lock) {
            for (b in data) raw += b.toInt() and 0xFF
            if (raw.size > 16_384) {
                val last = raw.last()
                raw.clear()
                raw += last
            }
            while (raw.size >= 4) {
                val start = findStart()
                if (start < 0) {
                    val last = raw.last()
                    raw.clear()
                    raw += last
                    break
                }
                if (start + 2 >= raw.size) {
                    // La cabecera llegó al final de esta lectura: el largo viene en la próxima.
                    dropUntil(start)
                    break
                }
                val n = raw[start + 2]
                if (n < 3) {
                    raw.removeAt(start)
                    continue
                }
                val end = start + n + 2
                if (end > raw.size) {
                    if (start > 0) dropUntil(start)
                    break
                }
                val frame = raw.subList(start, end).toIntArray()
                dropUntil(end)
                if (!checksumOk(frame)) continue
                try {
                    parse(frame)?.let(events::add)
                } catch (_: IndexOutOfBoundsException) {
                    // Trama más corta que el firmware habitual: se descarta, como el exe.
                }
            }
        }
        val listener = onNibp
        if (listener != null) {
            for (event in events) listener(event.status, event.cuffMmhg, event.sys, event.mean, event.dia)
        }
    }

    fun takePayload(): SampleWindow = synchronized(lock) {
        val deliveredPeaks = peaks
        val deliveredEcg = ecg
        val deliveredSpo2 = spo2
        val deliveredResp = resp
        peaks = ArrayList()
        ecg = emptyLeads()
        spo2 = ArrayList()
        resp = ArrayList()
        SampleWindow(
            spo2 = deliveredSpo2,
            ecg = deliveredEcg,
            ecgPeaks = deliveredPeaks,
            ecgInfo = ecgInfo.copy(),
            resp = deliveredResp,
            vitalSigns = vitals.copy(),
        )
    }

    fun peekVitals(): VitalSigns = synchronized(lock) { vitals.copy() }

    fun resetData() = synchronized(lock) {
        raw.clear()
        spo2 = ArrayList()
        ecg = emptyLeads()
        peaks = ArrayList()
        ecgInfo = EcgInfo()
        resp = ArrayList()
        vitals = VitalSigns()
    }

    /** Descarta sólo los bytes sin procesar; los signos y las ondas quedan. */
    fun dropPending() = synchronized(lock) {
        raw.clear()
    }

    fun resetNibp() = synchronized(lock) {
        vitals.nibp = NO_PAIR
    }

    private fun findStart(): Int {
        for (i in 0 until raw.size - 1) {
            if (raw[i] == 0x55 && raw[i + 1] == 0xAA) return i
        }
        return -1
    }

    private fun dropUntil(index: Int) {
        if (index <= 0) return
        if (index >= raw.size) {
            raw.clear()
            return
        }
        val keep = raw.subList(index, raw.size).toList()
        raw.clear()
        raw.addAll(keep)
    }

    private fun parse(frame: IntArray): NibpEvent? {
        when (frame[3]) {
            0x01 -> {
                if (ecgLen() < MAX_WAVE) {
                    val samples = frame.copyOfRange(4, frame.size - 1)
                    for ((lead, value) in Pm6750Protocol.LEADS.zip(samples.toList())) {
                        ecg.getValue(lead) += value
                    }
                }
            }
            0x02 -> {
                // Se lee todo antes de asignar: una trama corta no deja un dato a medias.
                val heartRate = frame[5]
                val respRate = frame[6]
                val info = decodeEcgInfo(frame)
                vitals.heartRate = formatCount(heartRate)
                vitals.respRate = formatCount(respRate)
                ecgInfo = info
            }
            0x03 -> {
                val sys = frame[6]
                val dia = frame[8]
                if (sys != 0 || dia != 0) {
                    vitals.nibp = "${formatCount(sys)}/${formatCount(dia)}"
                }
                return NibpEvent(frame[4], frame[5] * 2, sys, frame[7], dia)
            }
            0x04 -> {
                val saturation = frame[5]
                val pulse = frame[6]
                val saturationText = if (saturation <= 100) formatCount(saturation) else "-"
                val pulseText = if (pulse <= 250) formatCount(pulse) else "-"
                vitals.spo2Pulse = if (saturationText == "-" && pulseText == "-") NO_PAIR else "$saturationText/$pulseText"
            }
            0x05 -> {
                val tenths = frame[5] * 10 + frame[6]
                vitals.temperature = if (tenths == 0) "-" else "${tenths / 10}.${tenths % 10}"
            }
            0x30 -> {
                if (peaks.size < MAX_PEAKS) {
                    val sample = ecgLen()
                    val ms = java.lang.Math.round(sample * 1000.0 / Pm6750Protocol.ECG_SAMPLE_RATE_HZ * 10.0) / 10.0
                    peaks += EcgPeak(sample, ms)
                }
            }
            0xFE -> if (spo2.size < MAX_WAVE) spo2 += frame[4]
            0xFF -> if (resp.size < MAX_WAVE) resp += frame[4]
        }
        return null
    }

    private fun ecgLen(): Int = ecg.getValue(Pm6750Protocol.LEADS.first()).size

    private fun decodeEcgInfo(frame: IntArray): EcgInfo {
        val info = EcgInfo()
        val status = frame[4]
        info.weakSignal = status and 0x01 != 0
        info.leadOff = status and 0x02 != 0
        info.gain = ECG_GAIN_MODES[(status shr 2) and 0x03]
        info.mode = ECG_FILTER_MODES[(status shr 4) and 0x03]
        val payloadLen = frame.size - 5
        if (payloadLen >= 4) {
            val rawSt = frame[7]
            val st = if (rawSt > 127) rawSt - 256 else rawSt
            info.st = java.lang.Math.round(st / 100.0 * 100.0) / 100.0
        }
        if (payloadLen >= 5) {
            info.hrv = HRV_CODES[frame[8]] ?: "desconocido_0x%02X".format(frame[8])
        }
        return info
    }

    private data class NibpEvent(val status: Int, val cuffMmhg: Int, val sys: Int, val mean: Int, val dia: Int)

    companion object {
        const val MAX_WAVE = 2500
        const val MAX_PEAKS = 100
        private val ECG_GAIN_MODES = mapOf(0b00 to "x0.25", 0b01 to "x0.5", 0b10 to "x1", 0b11 to "x2")
        private val ECG_FILTER_MODES = mapOf(0b00 to "operacion", 0b01 to "monitor", 0b10 to "diagnostico")
        private val HRV_CODES = mapOf(
            0x00 to "analizando",
            0x01 to "normal",
            0x02 to "paro",
            0x03 to "fibrilacion_ventricular",
            0x04 to "R_sobre_T",
            0x05 to "extrasistoles_ventriculares_multiples",
            0x06 to "extrasistoles_ventriculares_dobles",
            0x07 to "extrasistole_ventricular",
            0x08 to "bigeminismo",
            0x09 to "trigeminismo",
            0x0A to "taquicardia",
            0x0B to "bradicardia",
            0x0C to "latido_perdido",
        )

        fun emptyLeads(): LinkedHashMap<String, MutableList<Int>> {
            val leads = LinkedHashMap<String, MutableList<Int>>()
            for (lead in Pm6750Protocol.LEADS) leads[lead] = ArrayList()
            return leads
        }

        fun checksumOk(frame: IntArray): Boolean {
            if (frame.size < 4) return false
            var sum = 0
            for (i in 2 until frame.size - 1) sum += frame[i]
            return (sum.inv() and 0xFF) == frame.last()
        }

        fun formatCount(value: Int): String = if (value == 0) "-" else value.toString()
    }
}
