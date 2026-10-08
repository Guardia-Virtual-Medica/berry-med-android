// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.protocol

/**
 * Comandos PC → PM6750. Misma secuencia que `src/pm6750_protocol.py` del monitor
 * de Windows: primero ganancia y modo, después los enables. La presión no se
 * habilita al conectar.
 */
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

    val LEADS = listOf("I", "II", "III", "aVR", "aVL", "aVF", "V")
    const val ECG_SAMPLE_RATE_HZ = 250.0

    private val ECG_GAIN = linkedMapOf("x0.25" to 0x01, "x0.5" to 0x02, "x1" to 0x03, "x2" to 0x04)
    private val ECG_MODE = linkedMapOf("operacion" to 0x01, "monitor" to 0x02, "diagnostico" to 0x03)
    private val NIBP_MODE = linkedMapOf("adulto" to 0x01, "nino" to 0x02, "neonato" to 0x03)
    private val NIBP_RANGE = mapOf("adulto" to 40..300, "nino" to 40..210, "neonato" to 40..140)

    fun checksum(n: Int, payload: ByteArray): Int {
        var sum = n
        for (b in payload) sum += b.toInt() and 0xFF
        return sum.inv() and 0xFF
    }

    fun buildCommand(a1: Int, a2: Int): ByteArray {
        val payload = byteArrayOf(a1.toByte(), a2.toByte())
        val n = payload.size + 2
        return byteArrayOf(
            0x55,
            0xAA.toByte(),
            n.toByte(),
            a1.toByte(),
            a2.toByte(),
            checksum(n, payload).toByte(),
        )
    }

    fun buildStartupCommands(config: DeviceTuning, log: (String) -> Unit = {}): List<Pair<Int, Int>> {
        val cmds = ArrayList<Pair<Int, Int>>()
        cmds += CMD_ECG_GAIN to pick(ECG_GAIN, config.ecgGain, "x1", "ECG_GAIN", log)
        cmds += CMD_ECG_MODE to pick(ECG_MODE, config.ecgMode, "monitor", "ECG_MODE", log)
        cmds += CMD_RESP_GAIN to pick(ECG_GAIN, config.respGain, "x1", "RESP_GAIN", log)
        cmds += listOf(
            CMD_ECG to 1,
            CMD_SPO2 to 1,
            CMD_TEMP to 1,
            CMD_SPO2_WAVE to 1,
            CMD_RESP_WAVE to 1,
        )
        return cmds
    }

    fun buildNibpCommands(config: DeviceTuning, log: (String) -> Unit = {}): List<Pair<Int, Int>> {
        val cmds = ArrayList<Pair<Int, Int>>()
        val (name, mode) = pickNamed(NIBP_MODE, config.nibpMode, "adulto", "NIBP_MODE", log)
        cmds += CMD_NIBP_MODE to mode
        val raw = config.nibpTargetPressureMmhg.trim()
        if (raw.isNotEmpty()) {
            val target = raw.toIntOrNull()
            if (target == null) {
                log("[PM6750][WARN] NIBP_TARGET_PRESSURE_MMHG inválido: ${raw}; se ignora.")
            } else {
                val range = NIBP_RANGE.getValue(name)
                if (target in range) {
                    cmds += CMD_NIBP_TARGET to (target / 2)
                } else {
                    log(
                        "[PM6750][WARN] NIBP_TARGET_PRESSURE_MMHG=$target fuera del " +
                            "rango ${range.first}-${range.last} para modo $name; se ignora.",
                    )
                }
            }
        }
        cmds += CMD_NIBP to 0x01
        return cmds
    }

    fun startupBytes(config: DeviceTuning, log: (String) -> Unit = {}): ByteArray {
        val out = ArrayList<Byte>()
        for ((a1, a2) in buildStartupCommands(config, log)) {
            out += buildCommand(a1, a2).toList()
        }
        return out.toByteArray()
    }

    fun nibpStop(): ByteArray = buildCommand(CMD_NIBP, 0x00)

    /** BIT5~2 del byte de estado. Terminales: terminado, detenido, manguito flojo, demoró. */
    fun nibpIsTerminal(status: Int): Boolean {
        val result = (status shr 2) and 0x0F
        return result == 0x0 || result == 0x2 || result == 0x4 || result == 0x5
    }

    private fun pick(
        table: Map<String, Int>,
        value: String?,
        defaultKey: String,
        label: String,
        log: (String) -> Unit,
    ): Int = pickNamed(table, value, defaultKey, label, log).second

    private fun pickNamed(
        table: Map<String, Int>,
        value: String?,
        defaultKey: String,
        label: String,
        log: (String) -> Unit,
    ): Pair<String, Int> {
        val key = value?.lowercase() ?: defaultKey
        val found = table[key]
        if (found != null) return key to found
        log("[PM6750][WARN] $label inválido: ${value}; válidos: ${table.keys.sorted()}. Se usa $defaultKey.")
        return defaultKey to table.getValue(defaultKey)
    }
}

data class DeviceTuning(
    val ecgGain: String = "x1",
    val ecgMode: String = "monitor",
    val respGain: String = "x1",
    val nibpMode: String = "adulto",
    val nibpTargetPressureMmhg: String = "",
)
