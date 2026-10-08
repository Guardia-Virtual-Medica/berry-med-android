package com.guardiavirtual.berrymed.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.guardiavirtual.berrymed.service.MonitorService

class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            action == UsbManager.ACTION_USB_DEVICE_DETACHED
        ) {
            MonitorService.start(context)
            context.startService(
                Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_USB_CHANGED),
            )
        }
    }
}
