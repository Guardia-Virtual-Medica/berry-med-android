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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.ui.MainActivity
import com.guardiavirtual.berrymed.usb.UsbHub

/**
 * Servicio en primer plano del tótem. Siempre de tipo `connectedDevice`: en
 * Android 15 `dataSync` no puede arrancar al encender la tablet y además tiene
 * un tope de 6 horas por día.
 */
class MonitorService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = ""
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.let { IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) }
            val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
            runtime().onUsbPermission(device, granted)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        showNotification("Esperando la plaqueta")
        acquireWakeLock()
        val filter = IntentFilter(UsbHub.ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, permissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val runtime = runtime()
        runtime.onNotification = { text -> showNotification(text) }
        runtime.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cada startForegroundService() espera su startForeground(), aunque el servicio ya estuviera.
        showNotification(lastNotification.ifBlank { "Esperando la plaqueta" }, force = true)
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

    @Synchronized
    private fun showNotification(text: String, force: Boolean = false) {
        if (text == lastNotification && !force) return
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
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            lastNotification = text
        } catch (error: Exception) {
            Log.e(TotemRuntime.TAG, "[SERVICIO] Android no dejó pasar a primer plano: $error")
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
