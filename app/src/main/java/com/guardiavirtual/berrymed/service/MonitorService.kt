package com.guardiavirtual.berrymed.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.config.ConfigStore
import com.guardiavirtual.berrymed.config.TotemConfig
import com.guardiavirtual.berrymed.net.MetricsPoster
import com.guardiavirtual.berrymed.net.PusherSessionController
import com.guardiavirtual.berrymed.protocol.BmDataParser
import com.guardiavirtual.berrymed.ui.MainActivity
import com.guardiavirtual.berrymed.usb.UsbOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var configStore: ConfigStore
    private val parser = BmDataParser()
    private lateinit var usb: UsbOrchestrator
    private val poster = MetricsPoster()
    private lateinit var pusher: PusherSessionController

    @Volatile private var config: TotemConfig = TotemConfig()
    @Volatile private var sending = false
    private var sessionStartedNs: Long = 0
    private var usbJob: Job? = null
    private var postJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        usb = UsbOrchestrator(this, parser) { line ->
            MonitorState.update { it.copy(statusLine = line) }
        }
        pusher = PusherSessionController(
            onStart = { handleStart() },
            onStop = { handleStop("evento stop") },
            onBloodPressure = { handleBloodPressure() },
        )
        startForeground(NOTIFICATION_ID, buildNotification("Berry Med activo"))
        scope.launch {
            configStore.configFlow.collect { cfg ->
                config = cfg
                usb.updateConfig(cfg)
                if (cfg.isConfigured()) {
                    pusher.connect(cfg)
                }
            }
        }
        usbJob = scope.launch { usbLoop() }
        postJob = scope.launch { postLoop() }
        scope.launch {
            while (isActive) {
                MonitorState.update {
                    it.copy(
                        berryConnected = usb.berryReader?.isConnected == true,
                        thermConnected = usb.thermometerReader != null,
                        relayPresent = usb.relay.isPresent,
                        pusherConnected = pusher.connected,
                        sessionActive = sending,
                        vitals = parser.vitalSigns.copy(),
                    )
                }
                delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_USB_CHANGED -> usb.refreshDevices()
            ACTION_RELOAD_CONFIG -> scope.launch {
                config = configStore.configFlow.first()
                usb.updateConfig(config)
                if (config.isConfigured()) pusher.connect(config)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        usb.disconnectAll()
        pusher.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun usbLoop() {
        while (true) {
            usb.refreshDevices()
            if (usb.berryReader?.isConnected != true) {
                MonitorState.update { it.copy(statusLine = "Esperando plaqueta USB…") }
            }
            delay(5000)
        }
    }

    private suspend fun postLoop() {
        while (true) {
            delay(1000)
            if (!sending) continue
            if (sessionExpired()) {
                handleStop("sin stop tras ${config.maxSessionMinutes} min")
                continue
            }
            val data = parser.takePayload()
            poster.post(config, data, usb.thermometerReader)
        }
    }

    private fun handleStart() {
        resetSessionState()
        sessionStartedNs = System.nanoTime()
        sending = true
        MonitorState.update { it.copy(sessionActive = true, statusLine = "Sesión activa — enviando métricas") }
    }

    private fun handleStop(reason: String) {
        sending = false
        sessionStartedNs = 0
        resetSessionState()
        MonitorState.update { it.copy(sessionActive = false, statusLine = "Sesión detenida ($reason)") }
    }

    private fun handleBloodPressure() {
        parser.resetNibp()
        usb.berryReader?.startNibp()
    }

    private fun resetSessionState() {
        usb.berryReader?.resetState()
        parser.resetData()
        usb.thermometerReader?.resetLatest()
    }

    private fun sessionExpired(): Boolean {
        if (config.maxSessionMinutes <= 0 || sessionStartedNs == 0L) return false
        val maxNs = config.maxSessionMinutes * 60L * 1_000_000_000L
        return System.nanoTime() - sessionStartedNs >= maxNs
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, BerryMedApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_USB_CHANGED = "com.guardiavirtual.berrymed.USB_CHANGED"
        const val ACTION_RELOAD_CONFIG = "com.guardiavirtual.berrymed.RELOAD_CONFIG"
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }
    }
}
