package com.guardiavirtual.berrymed.net

import android.util.Log
import com.guardiavirtual.berrymed.config.TotemConfig
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.Channel

class PusherSessionController(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onBloodPressure: () -> Unit,
) {
    private var pusher: Pusher? = null
    private var channel: Channel? = null

    @Volatile var connected: Boolean = false
        private set

    fun connect(config: TotemConfig) {
        disconnect()
        if (!config.isConfigured()) return
        val options = PusherOptions().setCluster(config.pusherCluster)
        pusher = Pusher(config.pusherKey, options).also { p ->
            p.connect(object : com.pusher.client.connection.ConnectionEventListener {
                override fun onConnectionStateChange(change: com.pusher.client.connection.ConnectionStateChange) {
                    connected = change.currentState == com.pusher.client.connection.ConnectionState.CONNECTED
                }

                override fun onError(message: String, code: String?, e: Exception?) {
                    Log.w(TAG, "Pusher error: $message")
                }
            }, com.pusher.client.connection.ConnectionState.ALL)
            channel = p.subscribe(config.publicChannel).also { ch ->
                ch.bind(config.startEventName) { onStart() }
                ch.bind(config.stopEventName) { onStop() }
                ch.bind("start-blood-pressure") { onBloodPressure() }
            }
        }
    }

    fun disconnect() {
        channel = null
        pusher?.disconnect()
        pusher = null
        connected = false
    }

    companion object {
        private const val TAG = "PusherSession"
    }
}
