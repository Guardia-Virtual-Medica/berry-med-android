// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import com.guardiavirtual.berrymed.config.BerryConfig
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
 * No se arma un cliente nuevo: desconectar uno que está entre reintentos lo
 * deja trabado en DISCONNECTING con sus dos hilos vivos.
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
    private val watchdog = ReconnectTimer(RETRY_NANOS)

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
        if (!config.pusherReady()) {
            listener.onConnection(false)
            return
        }
        if (config.publicChannel.startsWith("private-") || config.publicChannel.startsWith("presence-")) {
            log("[PUSHER] El canal tiene que ser público. Esta app no autentica canales privados.")
        }
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

    /** Se llama seguido. Si la librería se rindió hace [RETRY_NANOS], le pide otra tanda de intentos. */
    @Synchronized
    fun keepAlive(now: Long = System.nanoTime()) {
        val pusher = client ?: return
        if (!watchdog.due(now)) return
        if (pusher.connection.state != ConnectionState.DISCONNECTED) return
        log("[PUSHER] Desconectado hace más de ${RETRY_NANOS / 1_000_000_000L} s; se vuelve a conectar")
        pusher.connect()
    }

    @Synchronized
    fun stop() {
        disconnectClient()
        listener.onConnection(false)
    }

    private fun connect(config: BerryConfig) {
        val cluster = config.pusherCluster.ifBlank { "us2" }
        val options = PusherOptions().setCluster(cluster).setUseTLS(true)
        val pusher = Pusher(config.pusherKey, options)
        var bound = false
        client = pusher
        watchdog.reset()
        pusher.connect(object : ConnectionEventListener {
            override fun onConnectionStateChange(change: ConnectionStateChange) {
                // Un cliente viejo que termina de cerrarse no pisa el estado del nuevo.
                if (client !== pusher) return
                val up = change.currentState == ConnectionState.CONNECTED
                watchdog.onState(gaveUp = change.currentState == ConnectionState.DISCONNECTED, now = System.nanoTime())
                listener.onConnection(up)
                log("[PUSHER] ${change.currentState}")
                if (up && !bound) {
                    bind(pusher, config)
                    bound = true
                }
            }

            override fun onError(message: String?, code: String?, exception: Exception?) {
                log("[PUSHER] Error: ${message ?: ""} ${code ?: ""} ${exception?.message ?: ""}".trim())
            }
        }, ConnectionState.ALL)
        log("[PUSHER] Suscripción pedida en ${config.publicChannel}")
    }

    private fun disconnectClient() {
        val old = client
        client = null
        watchdog.reset()
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
    }
}

/**
 * Cuánto hace que la librería de Pusher se rindió (DISCONNECTED). Mientras
 * reintenta sola no cuenta. [due] avisa una vez por intervalo, así un corte
 * largo de red da una tanda de intentos cada tanto y no una por vuelta.
 */
class ReconnectTimer(private val afterNanos: Long) {
    private var downSince: Long? = null

    @Synchronized
    fun onState(gaveUp: Boolean, now: Long) {
        if (!gaveUp) {
            downSince = null
            return
        }
        if (downSince == null) downSince = now
    }

    @Synchronized
    fun due(now: Long): Boolean {
        val since = downSince ?: return false
        if (now - since < afterNanos) return false
        downSince = now
        return true
    }

    @Synchronized
    fun reset() {
        downSince = null
    }
}
