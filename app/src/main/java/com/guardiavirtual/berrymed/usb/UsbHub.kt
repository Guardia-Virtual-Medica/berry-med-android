// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Dispositivos USB del tótem: los puertos serie y el hub YKUSH3, con un permiso
 * por dispositivo. También lleva qué puertos ya se probaron y no son la
 * plaqueta: ésos quedan para el termómetro, que sólo habla cuando alguien mide.
 */
class UsbHub(context: Context) {
    private val appContext = context.applicationContext
    val manager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val busy = HashSet<Int>()
    private val notBerry = HashSet<Int>()
    private val requests = PermissionRequests(PERMISSION_GRACE_NANOS, PERMISSION_TIMEOUT_NANOS)

    fun permissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    /**
     * Pide permiso a los dispositivos del tótem que no lo tienen, de a uno:
     * plaqueta, hub YKUSH3 y termómetro. Android muestra un solo diálogo por
     * vez y los pedidos que llegan con uno abierto se pierden sin respuesta
     * (se vio en la tablet al reiniciar), así que el siguiente espera la
     * respuesta del anterior ([permissionAnswered]) o su vencimiento. No
     * bloquea: lo que ya tiene permiso se usa igual, y un dispositivo ajeno
     * al tótem (un teclado, un lector) no se pide ni frena a los demás.
     *
     * El otoscopio no: su único pedido es el de Android por el filtro, que trae
     * «Usar de forma predeterminada». Uno con `requestPermission` no la trae,
     * aparecía a la par y, aceptado, valía sólo hasta apagar el puerto. Para
     * volver a pedirlo está `OtoscopePermission`.
     */
    fun requestMissingPermissions() {
        val devices = totemDevices()
        val now = System.nanoTime()
        requests.observe(devices.map { it.deviceId }.toSet(), now)
        synchronized(notBerry) { notBerry.retainAll(manager.deviceList.values.map { it.deviceId }.toSet()) }
        val missing = devices.filter { !manager.hasPermission(it) && !isOtoscope(it) }
        val order = inAskOrder(missing.map { it.deviceId to UsbRole.of(it.vendorId, it.productId) })
        val next = requests.next(order, now) ?: return
        val device = missing.firstOrNull { it.deviceId == next } ?: return
        manager.requestPermission(device, permissionIntent())
    }

    /** Llegó la respuesta de un pedido (aceptado o rechazado): se puede pedir el siguiente. */
    fun permissionAnswered(deviceId: Int?) {
        requests.answered(deviceId)
    }

    /** El botón «Pedir permiso USB» y las filas del panel lateral: vuelve a pedir todo lo que falta, en fila. */
    fun forgetAsked() {
        requests.forget()
        requestMissingPermissions()
    }

    /** El pedido sin casilla, para el otoscopio sólo si no hay hub con el que hacer aparecer el de Android. */
    fun requestPermission(device: UsbDevice) {
        manager.requestPermission(device, permissionIntent())
    }

    /** Cuenta también el otoscopio, aunque no se le pida solo: la pantalla dice que falta. */
    fun permissionMissing(): Boolean = totemDevices().any { !manager.hasPermission(it) }

    /** Qué equipos del tótem están enchufados sin permiso, para las filas del panel lateral. */
    fun missingPermissions(): Set<UsbRole> =
        totemDevices().filter { !manager.hasPermission(it) }.map { UsbRole.of(it.vendorId, it.productId) }.toSet()

    fun mark(deviceId: Int): Boolean = synchronized(busy) { busy.add(deviceId) }

    fun unmark(deviceId: Int) {
        synchronized(busy) { busy.remove(deviceId) }
    }

    fun markNotBerry(deviceId: Int) {
        synchronized(notBerry) { notBerry.add(deviceId) }
    }

    fun clearNotBerry(deviceId: Int) {
        synchronized(notBerry) { notBerry.remove(deviceId) }
    }

    /** Puertos que todavía pueden ser la plaqueta. */
    fun berryCandidates(): List<UsbSerialDriver> {
        val excluded = synchronized(notBerry) { notBerry.toSet() }
        return serialDrivers().filter { it.device.deviceId !in excluded }
    }

    /** Puertos que el termómetro puede tomar. Ver [thermometerMayTake]. */
    fun thermometerCandidates(berryUp: Boolean): List<UsbSerialDriver> {
        val excluded = synchronized(notBerry) { notBerry.toSet() }
        return serialDrivers().filter { driver ->
            thermometerMayTake(driver.device.vendorId, driver.device.deviceId in excluded, berryUp)
        }
    }

    /** El hub YKUSH3, sólo si ya tiene permiso. */
    fun ykushDevice(): UsbDevice? =
        manager.deviceList.values.firstOrNull { isYkush(it) && manager.hasPermission(it) }

    fun ykushPlugged(): Boolean = manager.deviceList.values.any { isYkush(it) }

    /** Si hay enchufado un dispositivo con ese fabricante y producto, tenga permiso o no. */
    fun plugged(vendorId: Int, productId: Int): Boolean =
        manager.deviceList.values.any { it.vendorId == vendorId && it.productId == productId }

    /**
     * Qué equipos del Panel Local están enchufados, de una sola lectura de la
     * lista de USB: el panel lo pregunta cada medio segundo y cada lectura
     * arma la lista entera.
     */
    fun panelDevices(): PanelDevices = PanelDevices.of(manager.deviceList.values.map { it.vendorId to it.productId })

    fun isPresent(device: UsbDevice): Boolean =
        manager.deviceList.values.any { it.deviceId == device.deviceId }

    fun open(driver: UsbSerialDriver): SerialHandle? {
        val connection = manager.openDevice(driver.device) ?: return null
        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            return null
        }
        return try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            SerialHandle(driver.device, connection, port)
        } catch (_: Exception) {
            try {
                port.close()
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun serialDrivers(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).filter { driver ->
            val device = driver.device
            manager.hasPermission(device) && synchronized(busy) { device.deviceId !in busy }
        }
    }

    /**
     * Los puertos serie, el hub YKUSH3 y el otoscopio, con o sin permiso. El
     * otoscopio necesita permiso para el camino UVC del Panel Local, cuando la
     * tablet no lo lista como cámara. El adaptador del estetoscopio y el
     * auricular no: Android los maneja por su cuenta, como audio.
     */
    private fun totemDevices(): List<UsbDevice> {
        val serial = UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { it.device }
        val others = manager.deviceList.values.filter { isYkush(it) || KnownDevices.isOtoscope(it.vendorId, it.productId) }
        return (serial + others).distinctBy { it.deviceId }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.guardiavirtual.berrymed.USB_PERMISSION"
        const val BAUD = 115200

        /** El PM6750 es un STM32 VCP. Un puerto de ST mudo es la plaqueta que todavía no arrancó. */
        const val STM32_VENDOR = 0x0483

        /**
         * Android agrega el dispositivo a la lista un instante antes de darle
         * el permiso a la app predeterminada. Pedirlo en ese hueco deja un
         * diálogo de más en pantalla.
         */
        private const val PERMISSION_GRACE_NANOS = 3L * 1_000_000_000L

        /**
         * Lo que se espera la respuesta de un pedido antes de pasar al
         * siguiente: un diálogo que nadie contestó en un minuto.
         */
        const val PERMISSION_TIMEOUT_NANOS = 60L * 1_000_000_000L

        /** Los dispositivos sin permiso en el orden en que se piden: plaqueta, hub YKUSH3, termómetro. */
        fun inAskOrder(devices: List<Pair<Int, UsbRole>>): List<Int> =
            devices.sortedBy { (_, role) -> role.ordinal }.map { (deviceId, _) -> deviceId }

        fun isYkush(device: UsbDevice): Boolean = KnownDevices.isYkush3(device.vendorId, device.productId)

        fun isOtoscope(device: UsbDevice): Boolean = KnownDevices.isOtoscope(device.vendorId, device.productId)

        /**
         * El termómetro sólo manda una línea cuando alguien mide, así que no se
         * lo puede reconocer por lo que transmite. Toma un puerto que ya se
         * probó y no es la plaqueta, o cualquiera mientras la plaqueta está
         * conectada, porque hay una sola. Nunca uno de ST: ése es la plaqueta,
         * aunque esté muda, y tiene que quedar libre para cuando arranque.
         */
        fun thermometerMayTake(vendorId: Int, provenNotBerry: Boolean, berryUp: Boolean): Boolean {
            if (vendorId == STM32_VENDOR) return false
            return provenNotBerry || berryUp
        }
    }
}

/** Lo que el Panel Local necesita saber de lo enchufado, tenga permiso o no. */
data class PanelDevices(val ykush: Boolean, val stethoAdapter: Boolean, val otoscope: Boolean) {
    companion object {
        /** Desde los pares fabricante–producto de lo que ve Android. */
        fun of(devices: List<Pair<Int, Int>>): PanelDevices = PanelDevices(
            ykush = devices.any { (vendor, product) -> KnownDevices.isYkush3(vendor, product) },
            stethoAdapter = devices.any { (vendor, product) -> KnownDevices.isStethoAdapter(vendor, product) },
            otoscope = devices.any { (vendor, product) -> KnownDevices.isOtoscope(vendor, product) },
        )
    }
}

/**
 * Qué equipo del tótem es un dispositivo USB, para el orden de los pedidos y
 * las filas del panel lateral. En ese orden se piden los permisos.
 */
enum class UsbRole {
    BOARD,
    HUB,
    THERMOMETER,
    OTOSCOPE,
    ;

    companion object {
        /**
         * De los dispositivos del tótem (puertos serie, el YKUSH3 y el
         * otoscopio): un puerto de ST es la plaqueta, y cualquier otro puerto
         * serie, el termómetro, que nunca toma uno de ST.
         */
        fun of(vendorId: Int, productId: Int): UsbRole = when {
            KnownDevices.isYkush3(vendorId, productId) -> HUB
            KnownDevices.isOtoscope(vendorId, productId) -> OTOSCOPE
            vendorId == UsbHub.STM32_VENDOR -> BOARD
            else -> THERMOMETER
        }
    }
}

/**
 * Los pedidos de permiso USB, de a uno. Cada dispositivo se pide una sola vez:
 * el diálogo aparece arriba del kiosco y repetirlo solo lo taparía una y otra
 * vez. Un rechazo o un diálogo sin respuesta esperan al botón «Pedir permiso
 * USB» o a la fila del panel lateral. Un dispositivo que vuelve a aparecer
 * (otro deviceId, por ejemplo tras desenchufarlo) se pide de nuevo.
 *
 * Con un pedido en vuelo no se pide otro: Android muestra un solo diálogo y
 * los pedidos que llegan con uno abierto se pierden sin respuesta. El
 * siguiente sale cuando llega la respuesta ([answered]), cuando vence
 * [timeoutNanos] o cuando el del pedido desaparece o ya tiene permiso.
 */
class PermissionRequests(private val graceNanos: Long = 0L, private val timeoutNanos: Long = Long.MAX_VALUE) {
    private val asked = HashSet<Int>()
    private val firstSeen = HashMap<Int, Long>()
    private var inFlight: Int? = null
    private var inFlightAt = 0L

    /**
     * El dispositivo a pedir ahora, o null. [missing] son los que no tienen
     * permiso, en el orden en que se piden. El que devuelve queda en vuelo.
     * Si el primero sin pedir todavía está en su espera ([graceNanos]), no se
     * saltea: los demás esperan con él, así se respeta el orden.
     */
    @Synchronized
    fun next(missing: List<Int>, now: Long): Int? {
        val current = inFlight
        if (current != null) {
            if (current in missing && now - inFlightAt < timeoutNanos) return null
            // Venció, o ya no le falta el permiso: se libera, sin volver a pedirlo solo.
            inFlight = null
        }
        val candidate = missing.firstOrNull { it !in asked } ?: return null
        val seen = firstSeen.getOrPut(candidate) { now }
        if (now - seen < graceNanos) return null
        asked.add(candidate)
        inFlight = candidate
        inFlightAt = now
        return candidate
    }

    /**
     * Llegó la respuesta de un pedido, aceptado o rechazado. Sin el
     * dispositivo en la respuesta se libera igual: hay uno solo en vuelo.
     */
    @Synchronized
    fun answered(deviceId: Int?) {
        if (deviceId == null || deviceId == inFlight) inFlight = null
    }

    /** Vuelve a pedir todo. El que está en vuelo sigue esperando su respuesta: no se pide dos veces. */
    @Synchronized
    fun forget() {
        asked.clear()
        inFlight?.let(asked::add)
    }

    /** Anota los dispositivos que aparecieron y olvida los que se fueron, también el que estaba en vuelo. */
    @Synchronized
    fun observe(present: Set<Int>, now: Long) {
        for (deviceId in present) firstSeen.putIfAbsent(deviceId, now)
        firstSeen.keys.retainAll(present)
        asked.retainAll(present)
        if (inFlight != null && inFlight !in present) inFlight = null
    }
}

class SerialHandle(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    val port: UsbSerialPort,
) {
    fun write(bytes: ByteArray) {
        port.write(bytes, 1_000)
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)

    fun close() {
        try {
            port.close()
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}
