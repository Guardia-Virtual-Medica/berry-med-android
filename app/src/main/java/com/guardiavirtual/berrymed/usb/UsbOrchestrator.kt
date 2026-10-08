package com.guardiavirtual.berrymed.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.guardiavirtual.berrymed.config.TotemConfig
import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

enum class SerialRole { UNKNOWN, BERRY, THERMOMETER }

class UsbOrchestrator(
    private val context: Context,
    private val parser: BmDataParser,
    private val onStatus: (String) -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val relay = UsbRelay2Controller(usbManager)

    var berryReader: BerryUsbReader? = null
        private set
    var thermometerReader: ThermometerReader? = null
        private set

    private val roleByDeviceId = mutableMapOf<Int, SerialRole>()
    private var config: TotemConfig = TotemConfig()

    fun updateConfig(config: TotemConfig) {
        this.config = config
    }

    fun refreshDevices() {
        for (dev in usbManager.deviceList.values) {
            if (dev.vendorId == 0x16C0 && dev.productId == 0x05DF) {
                relay.bindIfRelay(dev)
                if (usbManager.hasPermission(dev)) {
                    relay.open()
                }
            }
        }
        val prober = UsbSerialProber(ProbeTable().also { it.addProduct(0x16C0, 0x05DF, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) })
        val drivers = prober.findAllDrivers(usbManager)
        for (driver in drivers) {
            val device = driver.device
            if (device.vendorId == 0x16C0 && device.productId == 0x05DF) continue
            if (!usbManager.hasPermission(device)) continue
            val role = roleByDeviceId[device.deviceId] ?: probeRole(driver)
            roleByDeviceId[device.deviceId] = role
            when (role) {
                SerialRole.BERRY -> attachBerry(driver)
                SerialRole.THERMOMETER -> if (config.thermometerEnabled) attachTherm(driver)
                SerialRole.UNKNOWN -> { }
            }
        }
    }

    private fun probeRole(driver: UsbSerialDriver): SerialRole {
        val conn = usbManager.openDevice(driver.device) ?: return SerialRole.UNKNOWN
        return try {
            val port = driver.ports.firstOrNull() ?: return SerialRole.UNKNOWN
            port.open(conn)
            port.setParameters(115200, 8, com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
            val buf = ByteArray(128)
            val deadline = System.currentTimeMillis() + 1500
            var berryHint = false
            var thermHint = false
            while (System.currentTimeMillis() < deadline) {
                val n = port.read(buf, 200)
                if (n <= 0) continue
                for (i in 0 until n - 1) {
                    if (buf[i] == 0x55.toByte() && buf[i + 1] == 0xAA.toByte()) berryHint = true
                }
                val text = String(buf, 0, n, Charsets.UTF_8)
                if (text.contains("valor_temperatura_ir")) thermHint = true
            }
            port.close()
            when {
                berryHint -> SerialRole.BERRY
                thermHint -> SerialRole.THERMOMETER
                else -> SerialRole.BERRY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed: ${e.message}")
            SerialRole.UNKNOWN
        } finally {
            conn.close()
        }
    }

    private fun attachBerry(driver: UsbSerialDriver) {
        if (berryReader?.isConnected == true) return
        val conn = usbManager.openDevice(driver.device) ?: return
        val port = driver.ports.first()
        try {
            port.open(conn)
        } catch (e: Exception) {
            conn.close()
            return
        }
        val reader = BerryUsbReader(
            port = port,
            parser = parser,
            deviceConfig = config.deviceConfig(),
            onLinkLost = { onStatus("Berry: enlace perdido, reconectando…") },
            powerCycle = {
                if (relay.isPresent) {
                    onStatus("Watchdog: reinicio de alimentación")
                    relay.restartDevice()
                } else {
                    false
                }
            },
        )
        if (reader.connect(allowRelayReset = relay.isPresent)) {
            berryReader = reader
            onStatus("Berry conectado")
        } else {
            onStatus("Berry: sin datos, reintentando…")
        }
    }

    private fun attachTherm(driver: UsbSerialDriver) {
        if (thermometerReader != null) return
        val conn = usbManager.openDevice(driver.device) ?: return
        val port = driver.ports.first()
        try {
            port.open(conn)
            port.setParameters(
                115200,
                8,
                com.hoho.android.usbserial.driver.UsbSerialPort.STOPBITS_1,
                com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE,
            )
        } catch (e: Exception) {
            conn.close()
            return
        }
        thermometerReader = ThermometerReader(port).also { it.start() }
        onStatus("Termómetro conectado")
    }

    fun requestPermission(device: UsbDevice) {
        // Permission flow handled in MainActivity via PendingIntent
    }

    fun disconnectAll() {
        berryReader?.abortConnection()
        berryReader = null
        thermometerReader?.stop()
        thermometerReader = null
        relay.close()
    }

    companion object {
        private const val TAG = "UsbOrchestrator"
    }
}
