// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed

import android.app.Application
import com.guardiavirtual.berrymed.service.TotemRuntime

class BerryMedApp : Application() {
    lateinit var runtime: TotemRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = TotemRuntime(this)
    }
}
