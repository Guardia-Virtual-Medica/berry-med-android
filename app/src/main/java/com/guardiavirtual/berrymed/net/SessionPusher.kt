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

/** Se suscribe al canal público y sólo escucha órdenes. Las mediciones no viajan por acá. */
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

    private var client: Pusher? = null
    private var identity: String? = null
    private var bound = false

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
        val cluster = config.pusherCluster.ifBlank { "us2" }
        val options = PusherOptions().setCluster(cluster).setUseTLS(true)
        val pusher = Pusher(config.pusherKey, options)
        pusher.connect(object : ConnectionEventListener {
            override fun onConnectionStateChange(change: ConnectionStateChange) {
                val up = change.currentState == ConnectionState.CONNECTED
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
        client = pusher
        log("[PUSHER] Suscripción pedida en ${config.publicChannel}")
    }

    fun stop() {
        bound = false
        try {
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
        listener.onConnection(false)
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
    }
}
