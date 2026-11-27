// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed

import android.app.Application
import com.guardiavirtual.berrymed.i18n.AppLocale
import com.guardiavirtual.berrymed.service.TotemRuntime

class BerryMedApp : Application() {
    lateinit var runtime: TotemRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        // Sin idioma elegido, español: antes de cualquier pantalla y del runtime (ver AppLocale).
        AppLocale.init(this)
        runtime = TotemRuntime(this)
    }
}
