// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.config.ykushPortOrDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YkushHubTest {
    @Test
    fun thePortGoesInTheLowBitsAndTheActionInTheHighOnes() {
        assertEquals(0x11.toByte(), YkushHub.command(port = 1, on = true))
        assertEquals(0x13.toByte(), YkushHub.command(port = 3, on = true))
        assertEquals(0x02.toByte(), YkushHub.command(port = 2, on = false))
        assertEquals(0x21.toByte(), YkushHub.statusCommand(port = 1))
    }

    @Test
    fun thePacketIsSixtyFourBytesAndRepeatsTheCommand() {
        val packet = YkushHub.packet(YkushHub.command(port = 2, on = true))
        assertEquals(64, packet.size)
        assertEquals(0x12.toByte(), packet[0])
        assertEquals(0x12.toByte(), packet[1])
        assertTrue(packet.drop(2).all { it == 0.toByte() })
    }

    @Test
    fun theDefaultStatePacketCarriesThePortAndTheState() {
        val on = YkushHub.defaultStatePacket(port = 3, on = true)
        assertEquals(64, on.size)
        assertEquals(listOf<Byte>(0x41, 0x03, 0x01), on.take(3))
        assertTrue(on.drop(3).all { it == 0.toByte() })
        assertEquals(listOf<Byte>(0x41, 0x01, 0x00), YkushHub.defaultStatePacket(port = 1, on = false).take(3))
    }

    @Test
    fun theHubAnswersOneWhenItAccepted() {
        assertTrue(YkushHub.ok(byteArrayOf(0x01, 0x11)))
        assertFalse(YkushHub.ok(byteArrayOf(0x00, 0x11)))
        assertFalse(YkushHub.ok(byteArrayOf()))
    }

    @Test
    fun theStatusReplyIsOneNOnAndZeroNOff() {
        assertEquals(true, YkushHub.stateFrom(byteArrayOf(0x01, 0x11)))
        assertEquals(true, YkushHub.stateFrom(byteArrayOf(0x01, 0x13)))
        assertEquals(false, YkushHub.stateFrom(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun aFailedStatusReplyIsUnknown() {
        assertNull(YkushHub.stateFrom(null))
        assertNull(YkushHub.stateFrom(byteArrayOf(0x00, 0x11)))
        assertNull(YkushHub.stateFrom(byteArrayOf(0x01)))
        assertNull(YkushHub.stateFrom(byteArrayOf(0x01, 0x7F)))
    }

    @Test
    fun aPortThatFailsLeavesOnlyThatOneUnknown() {
        val replies = listOf(byteArrayOf(0x01, 0x11), null, byteArrayOf(0x01, 0x03))
        assertEquals(listOf(true, null, false), replies.map(YkushHub::stateFrom))
    }

    @Test
    fun eachPortNamesItsDeviceAndASharedPortNamesBoth() {
        assertEquals(listOf("otoscopio", "estetoscopio", "auricular"), BerryConfig().ykushPortRoles())
        val shared = BerryConfig(ykushOtoscopePort = 1, ykushStethoscopePort = 1, ykushHeadsetPort = 3)
        assertEquals(listOf("otoscopio y estetoscopio", "libre", "auricular"), shared.ykushPortRoles())
    }

    @Test
    fun aPortTypedByHandMustBeOneToThree() {
        assertEquals(2, ykushPortOrDefault(" 2 ", default = 1))
        assertEquals(1, ykushPortOrDefault("4", default = 1))
        assertEquals(3, ykushPortOrDefault("", default = 3))
        assertEquals(3, ykushPortOrDefault(null, default = 3))
    }
}
