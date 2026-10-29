// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioPickTest {
    private fun input(id: Int, name: String, usb: Boolean = true) =
        AudioPick.Device(id = id, type = 0, name = name, isSource = true, isSink = false, usb = usb)

    private fun output(id: Int, name: String, usb: Boolean = true) =
        AudioPick.Device(id = id, type = 0, name = name, isSource = false, isSink = true, usb = usb)

    @Test
    fun theAdapterIsChosenByNameAmongSeveralUsbDevices() {
        val devices = listOf(
            input(1, "Built-in mic", usb = false),
            input(2, "HD video"),
            input(3, "USB Audio Device"),
            input(4, "Logitech USB Headset H390"),
        )
        assertEquals(3, AudioPick.stethoInput(devices, "USB Audio", "H390")?.id)
    }

    @Test
    fun whenTheHeadsetAlsoMatchesTheOtherOneWins() {
        val devices = listOf(
            input(4, "USB Audio - Logitech USB Headset H390"),
            input(5, "USB Audio"),
        )
        assertEquals(5, AudioPick.stethoInput(devices, "usb audio", "H390")?.id)
    }

    @Test
    fun withoutAMatchThereIsNoInputAndNeverTheBuiltInMic() {
        val devices = listOf(input(1, "USB Audio", usb = false), input(2, "HD video"), output(3, "USB Audio"))
        assertNull(AudioPick.stethoInput(devices, "USB Audio", "H390"))
        assertNull(AudioPick.stethoInput(listOf(input(5, "USB Audio")), "  ", "H390"))
    }

    @Test
    fun theHeadsetIsFoundByNameAndFallsBackToLogitech() {
        val devices = listOf(
            output(1, "Speaker", usb = false),
            output(2, "USB Audio"),
            output(3, "Logitech USB Headset"),
            output(4, "H390 Headset"),
        )
        assertEquals(4, AudioPick.headsetOutput(devices, "H390")?.id)
        assertEquals(3, AudioPick.headsetOutput(devices.take(3), "H390")?.id)
        assertNull(AudioPick.headsetOutput(devices.take(2), "H390"))
        assertNull(AudioPick.headsetOutput(listOf(input(9, "Logitech USB Headset")), "H390"))
    }
}
