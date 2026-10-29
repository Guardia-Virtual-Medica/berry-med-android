// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

/**
 * Elige con qué placa de audio escucha el estetoscopio y por cuál suena. No
 * tiene Android adentro: recibe una descripción de cada dispositivo, así la
 * regla se prueba sola.
 *
 * Nunca se usa el micrófono ni el parlante por defecto: con el micrófono del
 * otoscopio y el del H390 enchufados, el "por defecto" puede ser cualquiera, y
 * el parlante de la tablet se acopla con el estetoscopio.
 */
object AudioPick {
    /** Lo que importa de un `AudioDeviceInfo` para elegir. */
    data class Device(
        val id: Int,
        val type: Int,
        val name: String,
        val isSource: Boolean,
        val isSink: Boolean,
        val usb: Boolean,
    )

    /** El nombre de respaldo del auricular, si el configurado no aparece. */
    const val HEADSET_FALLBACK = "Logitech"

    /**
     * La entrada del estetoscopio: una entrada USB cuyo nombre contiene
     * [inputName] y que no es el auricular. El H390 también tiene micrófono y
     * puede declarar un nombre parecido ("USB Audio"): si el adaptador no está
     * y sólo coincide el auricular, es null. Elegirlo haría que el médico se
     * escuche a sí mismo por su propio auricular.
     */
    fun stethoInput(devices: List<Device>, inputName: String, headsetName: String): Device? {
        val wanted = inputName.trim()
        if (wanted.isEmpty()) return null
        val matches = devices.filter { it.usb && it.isSource && it.name.contains(wanted, ignoreCase = true) }
        return matches.firstOrNull { !isHeadset(it, headsetName) }
    }

    /** La salida del auricular: por el nombre configurado y, si no está, por "Logitech". */
    fun headsetOutput(devices: List<Device>, headsetName: String): Device? {
        val outputs = devices.filter { it.usb && it.isSink }
        val wanted = headsetName.trim()
        if (wanted.isNotEmpty()) {
            outputs.firstOrNull { it.name.contains(wanted, ignoreCase = true) }?.let { return it }
        }
        return outputs.firstOrNull { it.name.contains(HEADSET_FALLBACK, ignoreCase = true) }
    }

    private fun isHeadset(device: Device, headsetName: String): Boolean {
        val wanted = headsetName.trim()
        return (wanted.isNotEmpty() && device.name.contains(wanted, ignoreCase = true)) ||
            device.name.contains(HEADSET_FALLBACK, ignoreCase = true)
    }
}
