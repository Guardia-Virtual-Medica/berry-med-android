package com.guardiavirtual.berrymed.usb

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

class ThermometerReader(
    private val port: UsbSerialPort,
) {
    private val latest = AtomicReference<ThermReading?>(null)
    @Volatile private var running = false
    private var thread: Thread? = null

    data class ThermReading(val value: Double, val unit: String)

    fun start() {
        if (running) return
        running = true
        thread = Thread({ readLoop() }, "therm-reader").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        try {
            port.close()
        } catch (_: Exception) {
        }
    }

    fun getLatest(): ThermReading? = latest.get()

    fun resetLatest() {
        latest.set(null)
    }

    private fun readLoop() {
        val buffer = ByteArray(256)
        val lineBuffer = StringBuilder()
        while (running) {
            try {
                val n = port.read(buffer, 500)
                if (n <= 0) continue
                lineBuffer.append(String(buffer, 0, n, Charsets.UTF_8))
                var idx = lineBuffer.indexOf("\n")
                while (idx >= 0) {
                    val line = lineBuffer.substring(0, idx).trim()
                    lineBuffer.delete(0, idx + 1)
                    parseLine(line)
                    idx = lineBuffer.indexOf("\n")
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Therm read: ${e.message}")
                Thread.sleep(2000)
            }
        }
    }

    private fun parseLine(line: String) {
        try {
            val json = JSONObject(line)
            if (!json.has("valor_temperatura_ir")) return
            val value = json.getDouble("valor_temperatura_ir")
            val unit = json.optString("unit", "C").uppercase()
            latest.set(ThermReading(value, unit))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "Thermometer"
    }
}
