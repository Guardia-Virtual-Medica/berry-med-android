package com.guardiavirtual.berrymed.usb

import android.util.Log
import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.util.concurrent.atomic.AtomicLong

class BerryUsbReader(
    private val port: UsbSerialPort,
    private val parser: BmDataParser,
    private val deviceConfig: Pm6750Protocol.DeviceConfig,
    private val onLinkLost: () -> Unit,
    private val powerCycle: () -> Boolean,
) {
    @Volatile var isConnected = false
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private val lastFrameMs = AtomicLong(0)
    @Volatile var nibpRunning = false
        private set

    fun lastFrameSecondsAgo(): Double? {
        val t = lastFrameMs.get()
        if (t == 0L) return null
        return (System.nanoTime() - t) / 1_000_000_000.0
    }

    fun connect(allowRelayReset: Boolean = true): Boolean {
        try {
            port.open()
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            Log.w(TAG, "Open failed: ${e.message}")
            return false
        }
        parser.resetData()
        sendStartup()
        if (!waitForData(5000)) {
            Log.w(TAG, "No valid frames in 5s")
            abortConnection()
            if (allowRelayReset) {
                powerCycle()
            }
            return false
        }
        startReader()
        isConnected = true
        return true
    }

    fun abortConnection() {
        running = false
        thread?.join(1000)
        thread = null
        try {
            port.close()
        } catch (_: Exception) {
        }
        isConnected = false
    }

    fun resetState() {
        nibpRunning = false
        try {
            port.write(Pm6750Protocol.buildCommand(Pm6750Protocol.CMD_NIBP, 0x00), 1000)
        } catch (_: Exception) {
        }
        parser.resetData()
    }

    fun startNibp() {
        if (!isConnected || nibpRunning) return
        for ((a1, a2) in Pm6750Protocol.buildNibpCommands(deviceConfig)) {
            port.write(Pm6750Protocol.buildCommand(a1, a2), 1000)
            Thread.sleep(50)
        }
        nibpRunning = true
    }

    private fun sendStartup() {
        for ((a1, a2) in Pm6750Protocol.buildStartupCommands(deviceConfig)) {
            port.write(Pm6750Protocol.buildCommand(a1, a2), 1000)
            Thread.sleep(50)
        }
    }

    private fun waitForData(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            try {
                val n = port.read(buf, 300)
                if (n > 0) {
                    lastFrameMs.set(System.nanoTime())
                    parser.addData(buf.copyOf(n))
                    return true
                }
            } catch (_: Exception) {
                return false
            }
        }
        return lastFrameMs.get() != 0L
    }

    private fun startReader() {
        running = true
        thread = Thread({ readLoop() }, "berry-usb").also { it.start() }
    }

    private fun readLoop() {
        val buf = ByteArray(256)
        while (running) {
            try {
                val n = port.read(buf, 500)
                if (n > 0) {
                    lastFrameMs.set(System.nanoTime())
                    parser.addData(buf.copyOf(n))
                } else {
                    val ago = lastFrameSecondsAgo()
                    if (ago != null && ago > WATCHDOG_SILENCE_SEC) {
                        Log.w(TAG, "Watchdog: silence ${ago}s")
                        running = false
                        abortConnection()
                        parser.resetData()
                        powerCycle()
                        onLinkLost()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Read error: ${e.message}")
                running = false
                abortConnection()
                parser.resetData()
                onLinkLost()
                return
            }
        }
    }

    init {
        parser.onNibpParams = { status, _, _, _, _ ->
            val result = (status shr 2) and 0x0F
            if (result in listOf(0x0, 0x2, 0x4, 0x5)) {
                nibpRunning = false
                try {
                    port.write(Pm6750Protocol.buildCommand(Pm6750Protocol.CMD_NIBP, 0x00), 1000)
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "BerryUsb"
        const val WATCHDOG_SILENCE_SEC = 20.0
    }
}
