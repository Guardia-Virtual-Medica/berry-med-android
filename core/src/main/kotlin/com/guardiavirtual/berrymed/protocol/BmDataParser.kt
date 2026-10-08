package com.guardiavirtual.berrymed.protocol

class BmDataParser {
    private val rawBuffer = mutableListOf<Int>()
    private val ecgLeads = listOf("I", "II", "III", "aVR", "aVL", "aVF", "V")
    private val maxWaveformPoints = 2500
    private val maxPeaksPerWindow = 100
    private val ecgSampleRateHz = 250.0

    private val ecgInfoDefault = EcgInfo(null, null, null, null, null, null)

    private var spo2Wave = mutableListOf<Int>()
    private var ecgWave = ecgLeads.associateWith { mutableListOf<Int>() }.toMutableMap()
    private var ecgPeaks = mutableListOf<EcgPeak>()
    private var ecgInfo = ecgInfoDefault.copy()
    private var respWave = mutableListOf<Int>()
    var vitalSigns = VitalSigns()

    var onNibpParams: ((status: Int, cuff: Int, sys: Int, mean: Int, dia: Int) -> Unit)? = null

    fun addData(data: ByteArray) {
        data.forEach { rawBuffer.add(it.toInt() and 0xFF) }
        while (rawBuffer.size >= 4) {
            val startIdx = findPackageStart()
            if (startIdx == -1) {
                if (rawBuffer.isNotEmpty()) {
                    val last = rawBuffer.last()
                    rawBuffer.clear()
                    rawBuffer.add(last)
                }
                continue
            }
            if (startIdx > 0) {
                repeat(startIdx) { rawBuffer.removeAt(0) }
            }
            if (rawBuffer.size < 3) break
            val packageLength = rawBuffer[2]
            val endIdx = packageLength + 2
            if (endIdx > rawBuffer.size) break
            val pkg = rawBuffer.subList(0, endIdx).toList()
            repeat(endIdx) { rawBuffer.removeAt(0) }
            if (checkSum(pkg)) {
                parsePackage(pkg)
            }
        }
    }

    fun takePayload(): MetricsData {
        val peaks = ecgPeaks.toList()
        ecgPeaks = mutableListOf()
        val ecgCopy = ecgWave.mapValues { (_, v) -> v.toList() }
        ecgWave = ecgLeads.associateWith { mutableListOf<Int>() }.toMutableMap()
        val spo2Copy = spo2Wave.toList()
        spo2Wave = mutableListOf()
        val respCopy = respWave.toList()
        respWave = mutableListOf()
        return MetricsData(
            spo2 = spo2Copy,
            ecg = ecgCopy,
            ecgPeaks = peaks,
            ecgInfo = ecgInfo.copy(),
            resp = respCopy,
            vitalSigns = vitalSigns.copy(),
        )
    }

    fun resetData() {
        rawBuffer.clear()
        spo2Wave = mutableListOf()
        ecgWave = ecgLeads.associateWith { mutableListOf<Int>() }.toMutableMap()
        ecgPeaks = mutableListOf()
        ecgInfo = ecgInfoDefault.copy()
        respWave = mutableListOf()
        vitalSigns = VitalSigns()
    }

    fun resetNibp() {
        vitalSigns = vitalSigns.copy(nibp = VitalSigns.NO_VALUE)
    }

    private fun ecgWindowLen(): Int = ecgWave[ecgLeads[0]]?.size ?: 0

    private fun findPackageStart(): Int {
        for (i in 0 until rawBuffer.size - 1) {
            if (rawBuffer[i] == 0x55 && rawBuffer[i + 1] == 0xAA) return i
        }
        return -1
    }

    private fun checkSum(pkg: List<Int>): Boolean {
        val checksum = pkg.subList(2, pkg.size - 1).sum().inv() and 0xFF
        return checksum == pkg.last()
    }

    private fun formatValue(value: Int): String = if (value == 0) "-" else value.toString()

    private fun parsePackage(pkg: List<Int>) {
        val type = pkg[3]
        when (type) {
            0x01 -> {
                val samples = pkg.subList(4, pkg.size - 1)
                if (ecgWindowLen() < maxWaveformPoints) {
                    samples.forEachIndexed { idx, value ->
                        if (idx < ecgLeads.size) {
                            ecgWave.getValue(ecgLeads[idx]).add(value)
                        }
                    }
                }
            }
            0x02 -> {
                vitalSigns = vitalSigns.copy(
                    heartRate = formatValue(pkg[5]),
                    respRate = formatValue(pkg[6]),
                )
                ecgInfo = decodeEcgInfo(pkg)
            }
            0x03 -> {
                val sys = pkg[6]
                val dia = pkg[8]
                if (sys != 0 || dia != 0) {
                    vitalSigns = vitalSigns.copy(
                        nibp = "${formatValue(sys)}/${formatValue(dia)}",
                    )
                }
                onNibpParams?.invoke(pkg[4], pkg[5] * 2, sys, pkg[7], dia)
            }
            0x04 -> {
                val spo2 = pkg[5]
                val pulse = pkg[6]
                val spo2Val = if (spo2 <= 100) formatValue(spo2) else "-"
                val pulseVal = if (pulse <= 250) formatValue(pulse) else "-"
                vitalSigns = vitalSigns.copy(
                    spo2Pulse = when {
                        spo2Val == "-" && pulseVal == "-" -> VitalSigns.NO_VALUE
                        else -> "$spo2Val/$pulseVal"
                    },
                )
            }
            0x05 -> {
                val temp = (pkg[5] * 10 + pkg[6]) / 10.0
                vitalSigns = vitalSigns.copy(
                    temperature = if (temp == 0.0) "-" else {
                        val rounded = (temp * 10).toInt() / 10.0
                        if (rounded == rounded.toLong().toDouble()) {
                            rounded.toLong().toString()
                        } else {
                            rounded.toString()
                        }
                    },
                )
            }
            0x30 -> {
                if (ecgPeaks.size < maxPeaksPerWindow) {
                    val sample = ecgWindowLen()
                    ecgPeaks.add(
                        EcgPeak(
                            sample = sample,
                            ms = (sample * 1000.0 / ecgSampleRateHz).let { (it * 10).toInt() / 10.0 },
                        ),
                    )
                }
            }
            0xFE -> {
                if (spo2Wave.size < maxWaveformPoints) {
                    spo2Wave.add(pkg[4])
                }
            }
            0xFF -> {
                if (respWave.size < maxWaveformPoints) {
                    respWave.add(pkg[4])
                }
            }
        }
    }

    private fun decodeEcgInfo(pkg: List<Int>): EcgInfo {
        val status = pkg[4]
        val weak = status and 0x01 != 0
        val leadOff = status and 0x02 != 0
        val gainModes = mapOf(0 to "x0.25", 1 to "x0.5", 2 to "x1", 3 to "x2")
        val filterModes = mapOf(0 to "operacion", 1 to "monitor", 2 to "diagnostico")
        val gain = gainModes[(status shr 2) and 0x03]
        val mode = filterModes[(status shr 4) and 0x03]
        val payloadLen = pkg.size - 5
        var st: Double? = null
        var hrv: String? = null
        if (payloadLen >= 4) {
            var rawSt = pkg[7]
            if (rawSt > 127) rawSt -= 256
            st = (rawSt / 100.0).let { (it * 100).toInt() / 100.0 }
        }
        if (payloadLen >= 5) {
            hrv = hrvCodes[pkg[8]] ?: "desconocido_0x${pkg[8].toString(16)}"
        }
        return EcgInfo(leadOff, weak, gain, mode, st, hrv)
    }

    companion object {
        private val hrvCodes = mapOf(
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

        /** Build a valid frame: 55 AA N type ... checksum */
        fun frame(type: Int, vararg bytes: Int): ByteArray {
            val payload = byteArrayOf(type.toByte()) + bytes.map { it.toByte() }.toByteArray()
            val n = payload.size + 2
            val sum = (n + payload.sumOf { it.toInt() and 0xFF }).inv() and 0xFF
            return byteArrayOf(0x55, 0xAA.toByte(), n.toByte()) + payload + byteArrayOf(sum.toByte())
        }
    }
}
