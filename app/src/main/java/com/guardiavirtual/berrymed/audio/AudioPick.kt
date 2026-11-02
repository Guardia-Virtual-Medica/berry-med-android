// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER

/**
 * Elige con qué placa de audio escucha el estetoscopio y por cuál suena. No
 * tiene Android adentro: recibe una descripción de cada dispositivo, así la
 * regla se prueba sola.
 *
 * Suena por lo que esté enchufado en el jack de la tablet y, si no hay nada,
 * por el auricular USB H390 (ver [stethoOutput]). La videollamada con
 * Auriculares sigue el mismo orden y, como último respaldo, el parlante
 * ([callOutput]).
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

    /** `AudioDeviceInfo.TYPE_BUILTIN_SPEAKER`, acá para no traer Android. */
    const val TYPE_BUILTIN_SPEAKER = 2

    /** `AudioDeviceInfo.TYPE_WIRED_HEADSET`: auricular con micrófono en el jack de la tablet. */
    const val TYPE_WIRED_HEADSET = 3

    /** `AudioDeviceInfo.TYPE_WIRED_HEADPHONES`: auriculares sin micrófono en el jack de la tablet. */
    const val TYPE_WIRED_HEADPHONES = 4

    /** Si el tipo es algo enchufado en el jack de la tablet. */
    fun isJack(type: Int): Boolean = type == TYPE_WIRED_HEADSET || type == TYPE_WIRED_HEADPHONES

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

    /**
     * Por dónde suena el estetoscopio: primero lo que esté enchufado en el
     * jack de la tablet (Android lo lista sólo mientras está enchufado, en el
     * orden en que lo devuelve) y, si no hay nada, el auricular USB de
     * [headsetOutput]. El jack no necesita nombre: es de la tablet, no se
     * confunde con el adaptador. Nunca el parlante de la tablet, que se
     * acopla, ni el adaptador del estetoscopio: si no hay ninguno, es null.
     */
    fun stethoOutput(devices: List<Device>, headsetName: String): Device? =
        devices.firstOrNull { it.isSink && isJack(it.type) } ?: headsetOutput(devices, headsetName)

    /**
     * Por dónde sale la videollamada, entre los dispositivos de comunicación
     * de Android (que incluyen los auriculares del jack mientras están
     * enchufados). Con [choice] auriculares es la misma regla que el
     * estetoscopio, [stethoOutput]: el jack de la tablet, si no el H390 y, si
     * tampoco, el parlante: nunca se pierde el audio. Con parlante, el parlante
     * aunque haya algo en el jack. Nunca el adaptador del estetoscopio, aunque
     * sea el único USB: Chromium agrupa todo lo USB y, librado a sí mismo,
     * puede sacar la llamada por ese jack.
     */
    fun callOutput(devices: List<Device>, choice: String, headsetName: String): Device? {
        val speaker = devices.firstOrNull { it.type == TYPE_BUILTIN_SPEAKER }
        if (choice == CALL_AUDIO_SPEAKER) return speaker
        return stethoOutput(devices, headsetName) ?: speaker
    }

    private fun isHeadset(device: Device, headsetName: String): Boolean {
        val wanted = headsetName.trim()
        return (wanted.isNotEmpty() && device.name.contains(wanted, ignoreCase = true)) ||
            device.name.contains(HEADSET_FALLBACK, ignoreCase = true)
    }
}
