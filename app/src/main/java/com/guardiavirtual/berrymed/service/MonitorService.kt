// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
 *
 * Para la teleconsulta suma `camera|microphone`, pero sólo cuando una
 * pantalla de Berry Med está adelante ([promote]): Android no los da desde
 * segundo plano ni al encender la tablet. Una vez puestos, cada
 * `startForeground` repite los mismos (ver [ForegroundAccess]).
 */
class MonitorService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = ""

    /** Los tipos con los que está en primer plano; sólo suben. */
    private var types = ForegroundAccess.CONNECTED_DEVICE
    private var foreground = false

    /** Los tipos de antes de sumar cámara y micrófono, hasta que Android acepte los nuevos. */
    private var pendingUpgradeFrom: Int? = null
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
        if (intent?.action == ACTION_PROMOTE) promote()
        // Cada startForegroundService() espera su startForeground(), aunque el servicio ya estuviera.
        showNotification(lastNotification.ifBlank { "Esperando la plaqueta" }, force = true)
        runtime().start()
        return START_STICKY
    }

    /**
     * Una pantalla de Berry Med está adelante: se suman cámara y micrófono si
     * sus permisos están otorgados. Se vuelve a mirar que la app esté visible
     * de verdad (el pedido pudo tardar): desde segundo plano nunca se suma nada.
     */
    @Synchronized
    private fun promote() {
        if (Build.VERSION.SDK_INT < 30) return
        val next = ForegroundAccess.next(
            current = types,
            visible = appVisible(),
            cameraGranted = granted(Manifest.permission.CAMERA),
            microphoneGranted = granted(Manifest.permission.RECORD_AUDIO),
        )
        if (next == types) return
        val previous = types
        types = next
        // El startForeground que sigue en onStartCommand lleva los tipos nuevos; si Android los rechaza, vuelve a los de antes.
        pendingUpgradeFrom = previous
    }

    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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

    /**
     * El texto de la notificación. Cambiar el texto no vuelve a llamar a
     * `startForeground` (sólo lo hacen el arranque y cada `startForegroundService`):
     * se actualiza la notificación y listo.
     */
    @Synchronized
    private fun showNotification(text: String, force: Boolean = false) {
        if (text == lastNotification && !force) return
        val notification = buildNotification(text)
        if (foreground && !force) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                lastNotification = text
            } catch (error: Exception) {
                runtime().log("[SERVICIO] No se pudo actualizar la notificación: $error")
            }
            return
        }
        if (startForegroundWith(notification, types)) {
            lastNotification = text
            pendingUpgradeFrom?.let { from ->
                if (from != types) runtime().log("[SERVICIO] En primer plano como ${ForegroundAccess.describe(types)}")
            }
            pendingUpgradeFrom = null
            return
        }
        // Android rechazó los tipos nuevos: se vuelve a los de antes, que ya tenía.
        val previous = pendingUpgradeFrom ?: return
        pendingUpgradeFrom = null
        types = previous
        if (startForegroundWith(buildNotification(text), types)) lastNotification = text
    }

    private fun startForegroundWith(notification: android.app.Notification, wanted: Int): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, wanted)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
        runtime().foregroundTypes = wanted
        true
    } catch (error: Exception) {
        // Antes sólo iba a logcat; ahora también a la consola de Testing.
        runtime().log("[SERVICIO] Android no dejó pasar a primer plano como ${ForegroundAccess.describe(wanted)}: $error")
        false
    }

    private fun buildNotification(text: String): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
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
        if (Build.VERSION.SDK_INT >= 30 && !(ForegroundAccess.hasCamera(types) && ForegroundAccess.hasMicrophone(types))) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$TELE_HINT"))
        }
        return builder.build()
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
        const val ACTION_PROMOTE = "com.guardiavirtual.berrymed.PROMOTE"
        const val TELE_HINT = "Abrí Berry Med una vez para habilitar otoscopio y estetoscopio en la teleconsulta."

        /**
         * Lo llaman las pantallas de Berry Med en onResume: con la app visible,
         * el servicio suma cámara y micrófono para la teleconsulta.
         */
        fun promote(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MonitorService::class.java).setAction(ACTION_PROMOTE),
                )
            } catch (error: Exception) {
                Log.w(TotemRuntime.TAG, "[SERVICIO] No se pudo pedir cámara y micrófono: $error")
            }
        }
    }
}
