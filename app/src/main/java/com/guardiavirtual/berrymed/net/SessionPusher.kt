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
 * La librería deja de reintentar después de 6 intentos (unos 85 s) y queda
 * desconectada para siempre. Con la tablet sola eso es un tótem que no recibe
 * más el inicio de sesión, así que [keepAlive] arma un cliente nuevo si pasa
 * [RETRY_NANOS] sin conexión.
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
        applied = config
        if (!config.pusherReady()) {
            listener.onConnection(false)
            return
        }
        if (config.publicChannel.startsWith("private-") || config.publicChannel.startsWith("presence-")) {
            log("[PUSHER] El canal tiene que ser público. Esta app no autentica canales privados.")
        }
        connect(config)
    }

    /** Se llama seguido. Si hace rato que no hay conexión, arranca de cero. */
    @Synchronized
    fun keepAlive(now: Long = System.nanoTime()) {
        val config = applied ?: return
        if (client == null) return
        if (!watchdog.due(now)) return
        log("[PUSHER] Sin conexión hace más de ${RETRY_NANOS / 1_000_000_000L} s; se vuelve a conectar")
        disconnectClient()
        connect(config)
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
        watchdog.onState(connected = false, now = System.nanoTime())
        pusher.connect(object : ConnectionEventListener {
            override fun onConnectionStateChange(change: ConnectionStateChange) {
                // Un cliente viejo que termina de cerrarse no pisa el estado del nuevo.
                if (client !== pusher) return
                val up = change.currentState == ConnectionState.CONNECTED
                watchdog.onState(up, System.nanoTime())
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
        channel.bind(start, event { listener.onStart() })
        channel.bind(stop, event { listener.onStop() })
        channel.bind(BLOOD_PRESSURE, event { listener.onBloodPressure() })
        log("[PUSHER] Escuchando $start, $stop y $BLOOD_PRESSURE")
    }

    private fun event(block: () -> Unit) = SubscriptionEventListener { _: PusherEvent ->
        try {
            block()
        } catch (error: Exception) {
            log("[PUSHER] No se pudo procesar el evento: $error")
        }
    }

    companion object {
        const val BLOOD_PRESSURE = "start-blood-pressure"

        /** Más que lo que tarda la librería en agotar sus propios reintentos. */
        const val RETRY_NANOS = 60L * 1_000_000_000L
    }
}

/**
 * Cuánto hace que Pusher no está conectado. [due] avisa una vez por intervalo,
 * así un corte largo de red da un intento por minuto y no uno por vuelta.
 */
class ReconnectTimer(private val afterNanos: Long) {
    private var downSince: Long? = null

    @Synchronized
    fun onState(connected: Boolean, now: Long) {
        if (connected) {
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
