// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed

import android.app.Application
import com.guardiavirtual.berrymed.otoscope.NativeHeap
import com.guardiavirtual.berrymed.service.TotemRuntime

class BerryMedApp : Application() {
    lateinit var runtime: TotemRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = TotemRuntime(this)
        when (NativeHeap.zeroed()) {
            true -> runtime.log("[OTOSCOPIO] La memoria nativa llega en cero: cerrar una imagen que no arrancó no tira la app")
            false -> runtime.log("[OTOSCOPIO] La memoria nativa NO llega en cero: cerrar una imagen que no arrancó puede tirar la app")
            null -> runtime.log("[OTOSCOPIO] No se pudo comprobar si la memoria nativa llega en cero")
        }
    }
}
