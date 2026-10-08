package com.guardiavirtual.berrymed

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BerryMedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoreo Berry",
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "berry_monitor"
    }
}
