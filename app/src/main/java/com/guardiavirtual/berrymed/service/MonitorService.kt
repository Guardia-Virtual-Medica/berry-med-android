// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.ui.MainActivity
import com.guardiavirtual.berrymed.usb.UsbHub

class MonitorService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = ""
    private var lastBerryUp = false
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runtime().publish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        showNotification("Esperando la plaqueta", berryUp = false)
        acquireWakeLock()
        val filter = IntentFilter(UsbHub.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        val runtime = runtime()
        runtime.onNotification = { text, berryUp -> showNotification(text, berryUp) }
        runtime.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runtime().start()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runtime(): TotemRuntime = (application as BerryMedApp).runtime

    private fun showNotification(text: String, berryUp: Boolean) {
        if (text == lastNotification && berryUp == lastBerryUp) return
        lastNotification = text
        lastBerryUp = berryUp
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val preferred = if (berryUp) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(NOTIFICATION_ID, notification, preferred)
            } catch (_: SecurityException) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL, "Tótem Berry", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "berrymed:totem")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    companion object {
        private const val CHANNEL = "berrymed"
        private const val NOTIFICATION_ID = 1
    }
}
