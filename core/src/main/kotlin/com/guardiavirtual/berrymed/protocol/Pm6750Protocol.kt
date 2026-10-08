package com.guardiavirtual.berrymed.protocol

object Pm6750Protocol {
    val HEADER = byteArrayOf(0x55, 0xAA.toByte())

    const val CMD_ECG = 0x01
    const val CMD_NIBP = 0x02
    const val CMD_SPO2 = 0x03
    const val CMD_TEMP = 0x04
    const val CMD_ECG_GAIN = 0x07
    const val CMD_ECG_MODE = 0x08
    const val CMD_NIBP_MODE = 0x09
    const val CMD_NIBP_TARGET = 0x0A
    const val CMD_RESP_GAIN = 0x0F
    const val CMD_SPO2_WAVE = 0xFE
    const val CMD_RESP_WAVE = 0xFF

    private val ECG_GAIN_VALUES = mapOf(
        "x0.25" to 0x01,
        "x0.5" to 0x02,
        "x1" to 0x03,
        "x2" to 0x04,
    )
    private val ECG_MODE_VALUES = mapOf(
        "operacion" to 0x01,
        "monitor" to 0x02,
        "diagnostico" to 0x03,
    )
    private val NIBP_MODE_VALUES = mapOf(
        "adulto" to 0x01,
        "nino" to 0x02,
        "neonato" to 0x03,
    )
    private val NIBP_TARGET_RANGE = mapOf(
        "adulto" to (40 to 300),
        "nino" to (40 to 210),
        "neonato" to (40 to 140),
    )

    fun checksum(n: Int, payload: ByteArray): Int =
        (n + payload.sumOf { it.toInt() and 0xFF }).inv() and 0xFF

    fun buildCommand(a1: Int, a2: Int): ByteArray {
        val payload = byteArrayOf(a1.toByte(), a2.toByte())
        val n = payload.size + 2
        return HEADER + byteArrayOf(n.toByte()) + payload + byteArrayOf(checksum(n, payload).toByte())
    }

    data class DeviceConfig(
        val ecgGain: String = "x1",
        val ecgMode: String = "monitor",
        val respGain: String = "x1",
        val nibpMode: String = "adulto",
        val nibpTargetPressureMmhg: Int? = null,
    )

    fun buildStartupCommands(config: DeviceConfig = DeviceConfig()): List<Pair<Int, Int>> {
        val gain = pick(ECG_GAIN_VALUES, config.ecgGain, "x1")
        val mode = pick(ECG_MODE_VALUES, config.ecgMode, "monitor")
        val respGain = pick(ECG_GAIN_VALUES, config.respGain, "x1")
        return buildList {
            add(CMD_ECG_GAIN to gain)
            add(CMD_ECG_MODE to mode)
            add(CMD_RESP_GAIN to respGain)
            add(CMD_ECG to 1)
            add(CMD_SPO2 to 1)
            add(CMD_TEMP to 1)
            add(CMD_SPO2_WAVE to 1)
            add(CMD_RESP_WAVE to 1)
        }
    }

    fun buildNibpCommands(config: DeviceConfig = DeviceConfig()): List<Pair<Int, Int>> {
        val nibpName = config.nibpMode.lowercase()
        val nibpMode = NIBP_MODE_VALUES[nibpName] ?: NIBP_MODE_VALUES["adulto"]!!
        val cmds = mutableListOf(CMD_NIBP_MODE to nibpMode)
        val target = config.nibpTargetPressureMmhg
        if (target != null) {
            val range = NIBP_TARGET_RANGE[nibpName] ?: NIBP_TARGET_RANGE["adulto"]!!
            if (target in range.first..range.second) {
                cmds.add(CMD_NIBP_TARGET to (target / 2))
            }
        }
        cmds.add(CMD_NIBP to 0x01)
        return cmds
    }

    private fun pick(table: Map<String, Int>, value: String, defaultKey: String): Int {
        val key = value.lowercase()
        return table[key] ?: table[defaultKey]!!
    }
}
