// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.Environment
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.PusherEvent
import com.pusher.client.channel.SubscriptionEventListener
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange

/**
 * Se suscribe al canal público y sólo escucha órdenes. Las mediciones no viajan por acá.
 *
 * La librería deja de reintentar después de 6 intentos (unos 85 s) y queda en
 * DISCONNECTED para siempre. Con la tablet sola eso es un tótem que no recibe
 * más el inicio de sesión, así que [keepAlive] le vuelve a pedir `connect()`
 * al mismo cliente, que arranca otra tanda de intentos y re-suscribe el canal.
 * No se arma un cliente nuevo para eso: desconectar uno que está entre
 * reintentos lo deja trabado en DISCONNECTING con sus dos hilos vivos.
 *
 * El único caso en que sí se rearma es un cliente trabado en CONNECTING (la red
 * acepta la conexión y nunca contesta el TLS): ahí la librería no tiene timeout.
 * Como ese cliente se pierde, cada rearmado espera el doble que el anterior.
 */
class SessionPusher(
    private val log: (String) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        fun onStart()
        fun onStop()
        fun onBloodPressure()
        fun onConnection(connected: Boolean)
    }

    @Volatile private var client: Pusher? = null
    private var identity: String? = null
    private var applied: BerryConfig? = null
    private val gaveUp = ReconnectTimer(RETRY_NANOS)
    private val stuck = ReconnectTimer(STUCK_NANOS, maxNanos = STUCK_MAX_NANOS)

    @Synchronized
    fun apply(config: BerryConfig) {
        val next = listOf(
            config.pusherKey,
            config.pusherCluster,
            config.publicChannel,
            config.startEventName,
            config.stopEventName,
        ).joinToString("|")
        if (next == identity && client != null) return
        stop()
        identity = next
        applied = null
        if (!config.channelIsPublic()) {
            log("[PUSHER] El canal tiene que ser público. Esta app no autentica canales privados.")
        }
        if (!config.pusherReady()) {
            listener.onConnection(false)
            return
        }
        applied = config
        connectSafely(config)
    }

    /** Se llama seguido. Vuelve a intentar si la librería se rindió o si quedó trabada conectando. */
    @Synchronized
    fun keepAlive(now: Long = System.nanoTime()) {
        val pusher = client ?: return
        val state = pusher.connection.state
        if (gaveUp.due(now) && state == ConnectionState.DISCONNECTED) {
            log("[PUSHER] Desconectado hace más de ${RETRY_NANOS / 1_000_000_000L} s; se vuelve a conectar")
            pusher.connect()
            return
        }
        if (stuck.due(now) && state == ConnectionState.CONNECTING) {
            val config = applied ?: return
            log("[PUSHER] Trabado conectando; se arma un cliente nuevo")
            disconnectClient(resetTimers = false)
            connectSafely(config)
        }
    }

    @Synchronized
    fun stop() {
        disconnectClient(resetTimers = true)
        listener.onConnection(false)
    }

    private fun connectSafely(config: BerryConfig) {
        try {
            connect(config)
        } catch (error: Exception) {
            // Una clave o un cluster con caracteres inválidos rompen la URL del
            // websocket. Sin esto el tótem se cerraba en cada arranque.
            log("[PUSHER] No se pudo armar la conexión: $error")
            client = null
            listener.onConnection(false)
        }
    }

    private fun connect(config: BerryConfig) {
        val cluster = config.pusherCluster.ifBlank { Environment.fromKey(config.environment).pusherCluster }
        val options = PusherOptions().setCluster(cluster).setUseTLS(true)
        val pusher = Pusher(config.pusherKey, options)
        client = pusher
        // Se suscribe antes de conectar: la librería manda la suscripción al
        // conectar y la repite sola en cada reconexión. Suscribirse desde el
        // aviso de CONNECTED a veces la mandaba dos veces, según qué aviso
        // corriera primero, y Pusher respondía "Existing subscription".
        bind(pusher, config)
        pusher.connect(object : ConnectionEventListener {
            override fun onConnectionStateChange(change: ConnectionStateChange) {
                // Corre en un hilo de la librería: una excepción acá cierra la app.
                try {
                    if (client !== pusher) {
                        // Un cliente reemplazado que igual terminó de conectar queda
                        // abierto para siempre si nadie lo cierra.
                        if (change.currentState == ConnectionState.CONNECTED) pusher.disconnect()
                        return
                    }
                    val state = change.currentState
                    val now = System.nanoTime()
                    gaveUp.onState(down = state == ConnectionState.DISCONNECTED, now = now)
                    stuck.onState(down = state == ConnectionState.CONNECTING, now = now)
                    listener.onConnection(state == ConnectionState.CONNECTED)
                    log("[PUSHER] $state")
                } catch (error: Exception) {
                    log("[PUSHER] Error al procesar el cambio de estado: $error")
                }
            }

            override fun onError(message: String?, code: String?, exception: Exception?) {
                log("[PUSHER] Error: ${message ?: ""} ${code ?: ""} ${exception?.message ?: ""}".trim())
            }
        }, ConnectionState.ALL)
        log("[PUSHER] Suscripción pedida en ${config.publicChannel}")
    }

    private fun disconnectClient(resetTimers: Boolean) {
        val old = client
        client = null
        gaveUp.reset()
        if (resetTimers) stuck.reset()
        try {
            old?.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun bind(pusher: Pusher, config: BerryConfig) {
        val channel = pusher.subscribe(config.publicChannel)
        val start = config.startEventName.ifBlank { "start-monitoring" }
        val stop = config.stopEventName.ifBlank { "stop-monitoring" }
        channel.bind(start, event(pusher) { listener.onStart() })
        channel.bind(stop, event(pusher) { listener.onStop() })
        channel.bind(BLOOD_PRESSURE, event(pusher) { listener.onBloodPressure() })
        log("[PUSHER] Escuchando $start, $stop y $BLOOD_PRESSURE")
    }

    private fun event(pusher: Pusher, block: () -> Unit) = SubscriptionEventListener { _: PusherEvent ->
        // Un cliente reemplazado al cambiar la configuración puede terminar de
        // conectar igual; sus órdenes no cuentan.
        if (client !== pusher) return@SubscriptionEventListener
        try {
            block()
        } catch (error: Exception) {
            log("[PUSHER] No se pudo procesar el evento: $error")
        }
    }

    companion object {
        const val BLOOD_PRESSURE = "start-blood-pressure"

        /** Desde que la librería se rinde. Igual a su espera más larga entre intentos. */
        const val RETRY_NANOS = 30L * 1_000_000_000L

        /** En CONNECTING más que esto es una red que no contesta, no un intento lento. */
        const val STUCK_NANOS = 5L * 60L * 1_000_000_000L
        const val STUCK_MAX_NANOS = 60L * 60L * 1_000_000_000L
    }
}

/**
 * Cuánto hace que la conexión está en un estado malo. [due] avisa una vez por
 * intervalo, así un corte largo da un intento cada tanto y no uno por vuelta.
 * Con [maxNanos] mayor que [baseNanos] el intervalo se duplica en cada aviso
 * hasta ese tope, y vuelve al inicio cuando el estado malo termina.
 */
class ReconnectTimer(private val baseNanos: Long, private val maxNanos: Long = baseNanos) {
    private var downSince: Long? = null
    private var intervalNanos = baseNanos

    @Synchronized
    fun onState(down: Boolean, now: Long) {
        if (!down) {
            downSince = null
            intervalNanos = baseNanos
            return
        }
        if (downSince == null) downSince = now
    }

    @Synchronized
    fun due(now: Long): Boolean {
        val since = downSince ?: return false
        if (now - since < intervalNanos) return false
        downSince = now
        intervalNanos = minOf(intervalNanos * 2, maxNanos)
        return true
    }

    @Synchronized
    fun reset() {
        downSince = null
        intervalNanos = baseNanos
    }
}
